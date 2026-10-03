package ai.meteor.dsh.runtime

import ai.meteor.ubuntu.runtime.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import android.util.Base64
import java.security.SecureRandom

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import ai.meteor.ubuntu.runtime.RootAccessState.Checking
import ai.meteor.ubuntu.runtime.RootAccessState.Denied
import ai.meteor.ubuntu.runtime.RootAccessState.Granted
import ai.meteor.ubuntu.runtime.RootAccessState.NotRequired
import ai.meteor.ubuntu.runtime.RootAccessState.Required
import ai.meteor.dsh.runtime.RuntimePhase.Failed
import ai.meteor.dsh.runtime.RuntimePhase.Installing
import ai.meteor.dsh.runtime.RuntimePhase.NotInstalled
import ai.meteor.dsh.runtime.RuntimePhase.Ready
import ai.meteor.dsh.runtime.RuntimePhase.Running
import ai.meteor.dsh.runtime.RuntimePhase.Starting
import ai.meteor.dsh.runtime.RuntimePhase.Stopping
import ai.meteor.dsh.runtime.RuntimePhase.Unavailable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object RuntimeStateStore {
    private val mutableState = MutableStateFlow(RuntimeUiState())
    val state = mutableState.asStateFlow()

    fun set(value: RuntimeUiState) {
        mutableState.value = value
    }

    fun appendLog(line: String) {
        mutableState.value = mutableState.value.copy(
            logTail = (mutableState.value.logTail + line).takeLast(MAX_UI_LOG_LINES),
        )
    }
}

class RuntimeManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val artifacts = RuntimeArtifactRepository(context)
    private val installer = RootfsInstaller(context, artifacts)
    private val dshInstaller = DshInstaller(context)
    private val rootAccess = RootAccessController()
    private val supervisor = UbuntuProcessSupervisor(context, rootAccess)
    private val operationMutex = Mutex()
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private var sessionMode = if (preferences.getBoolean(PREFERENCE_REMEMBER_MODE, false)) {
        preferences.getString(PREFERENCE_RUNTIME_MODE, null)
            ?.let { saved -> RuntimeMode.entries.firstOrNull { it.name == saved } } ?: RuntimeMode.Proot
    } else RuntimeMode.Proot
    private var launchHandled = false

    fun setRememberRuntimeMode(remember: Boolean) {
        preferences.edit(commit = true) {
            putBoolean(PREFERENCE_REMEMBER_MODE, remember)
            if (remember) putString(PREFERENCE_RUNTIME_MODE, sessionMode.name)
            else remove(PREFERENCE_RUNTIME_MODE)
        }
        RuntimeStateStore.set(RuntimeStateStore.state.value.copy(rememberRuntimeMode = remember))
    }

    /** One automatic launch per process; recreating the activity must not restart a stopped runtime. */
    fun claimRememberedLaunch(): Boolean {
        if (launchHandled) return false
        launchHandled = true
        return preferences.getBoolean(PREFERENCE_REMEMBER_MODE, false) &&
            RuntimeStateStore.state.value.phase == Ready
    }

    fun selectRuntimeMode(mode: RuntimeMode) {
        val current = RuntimeStateStore.state.value
        if (current.isBusy || current.phase == Running || current.rootAccess == Checking) return
        sessionMode = mode
        if (preferences.getBoolean(PREFERENCE_REMEMBER_MODE, false)) {
            preferences.edit(commit = true) { putString(PREFERENCE_RUNTIME_MODE, mode.name) }
        }
        RuntimeStateStore.set(
            current.copy(
                runtimeMode = mode,
                rememberRuntimeMode = preferences.getBoolean(PREFERENCE_REMEMBER_MODE, false),
                rootAccess = when (mode) {
                    RuntimeMode.Proot, RuntimeMode.Proroot -> NotRequired
                    RuntimeMode.Chroot -> if (
                        current.runtimeMode == RuntimeMode.Chroot && current.rootAccess == Granted
                    ) Granted else Required
                },
            ),
        )
    }

    suspend fun requestRootAccess() = operationMutex.withLock {
        if (selectedMode() != RuntimeMode.Chroot) return@withLock
        RuntimeStateStore.set(RuntimeStateStore.state.value.copy(rootAccess = Checking))
        val granted = rootAccess.request()
        RuntimeStateStore.set(
            RuntimeStateStore.state.value.copy(
                rootAccess = if (selectedMode() != RuntimeMode.Chroot) {
                    NotRequired
                } else if (granted) {
                    Granted
                } else {
                    Denied
                },
            ),
        )
    }

    suspend fun probe() = operationMutex.withLock {
        if (RuntimeStateStore.state.value.phase in setOf(Running, Starting, Stopping)) return@withLock
        runCatching {
            val manifest = artifacts.readManifest()
            val mode = selectedMode()
            val rootState = rootStateFor(mode)
            when {
                !manifest.available || !dshInstaller.readManifest().available -> RuntimeUiState(
                    phase = Unavailable,
                    runtimeVersion = manifest.runtimeVersion,
                    detail = RuntimeMessage(RuntimeMessageKind.ArtifactsUnavailable),
                    runtimeMode = mode,
                    rememberRuntimeMode = preferences.getBoolean(PREFERENCE_REMEMBER_MODE, false),
                    rootAccess = rootState,
                )
                installer.probe(manifest) != null && dshInstaller.probe() != null -> RuntimeUiState(
                    phase = Ready,
                    runtimeVersion = manifest.runtimeVersion,
                    detail = RuntimeMessage(RuntimeMessageKind.RuntimeReady),
                    runtimeMode = mode,
                    rememberRuntimeMode = preferences.getBoolean(PREFERENCE_REMEMBER_MODE, false),
                    rootAccess = rootState,
                )
                else -> RuntimeUiState(
                    phase = NotInstalled,
                    runtimeVersion = manifest.runtimeVersion,
                    detail = RuntimeMessage(RuntimeMessageKind.RuntimeNotInstalled),
                    runtimeMode = mode,
                    rememberRuntimeMode = preferences.getBoolean(PREFERENCE_REMEMBER_MODE, false),
                    rootAccess = rootState,
                )
            }
        }.getOrElse(::failureState).also(RuntimeStateStore::set)
    }

    suspend fun install() = operationMutex.withLock {
        runCatching {
            val manifest = artifacts.readManifest()
            val mode = selectedMode()
            val rootState = rootStateFor(mode)
            RuntimeStateStore.set(
                RuntimeUiState(
                    phase = Installing,
                    runtimeVersion = manifest.runtimeVersion,
                    detail = RuntimeMessage(RuntimeMessageKind.Installing),
                    progress = 0f,
                    runtimeMode = mode,
                    rememberRuntimeMode = preferences.getBoolean(PREFERENCE_REMEMBER_MODE, false),
                    rootAccess = rootState,
                ),
            )
            installer.install(manifest) { progress, message ->
                RuntimeStateStore.set(
                    RuntimeStateStore.state.value.copy(
                        phase = Installing,
                        detail = message,
                        progress = progress * 0.6f,
                    ),
                )
            }
            dshInstaller.install { progress, _ ->
                RuntimeStateStore.set(RuntimeStateStore.state.value.copy(
                    detail = RuntimeMessage(RuntimeMessageKind.Installing), progress = 0.6f + progress * 0.4f,
                ))
            }
            RuntimeUiState(
                phase = Ready,
                runtimeVersion = manifest.runtimeVersion,
                detail = RuntimeMessage(RuntimeMessageKind.RuntimeReady),
                runtimeMode = mode,
                rememberRuntimeMode = preferences.getBoolean(PREFERENCE_REMEMBER_MODE, false),
                rootAccess = rootState,
            )
        }.getOrElse(::failureState).also(RuntimeStateStore::set)
    }

    suspend fun start() = operationMutex.withLock {
        runCatching {
            val manifest = artifacts.readManifest()
            val installed = requireNotNull(installer.probe(manifest)) { "Runtime is not installed" }
            val mode = selectedMode()
            val rootState = if (mode == RuntimeMode.Chroot) {
                RuntimeStateStore.set(RuntimeStateStore.state.value.copy(rootAccess = Checking))
                if (!rootAccess.request()) throw RootAccessDeniedException()
                Granted
            } else {
                NotRequired
            }
            RuntimeStateStore.set(
                RuntimeUiState(
                    phase = Starting,
                    runtimeVersion = manifest.runtimeVersion,
                    detail = RuntimeMessage(RuntimeMessageKind.Starting),
                    runtimeMode = mode,
                    rememberRuntimeMode = preferences.getBoolean(PREFERENCE_REMEMBER_MODE, false),
                    rootAccess = rootState,
                ),
            )
            val ready = CompletableDeferred<Int>()
            val token = Base64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) },
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val payload = requireNotNull(dshInstaller.probe()) { "Install DSH before starting" }
            try {
                supervisor.start(
                    runtime = installed,
                    mode = mode,
                    command = dshInstaller.command(payload, token),
                    onLog = { line ->
                        RuntimeStateStore.appendLog(line)
                        Regex("dsh-mobile gateway: http://127\\.0\\.0\\.1:(\\d+)")
                            .find(line)?.groupValues?.get(1)?.toIntOrNull()?.let { ready.complete(it) }
                    },
                    onExit = { exitCode ->
                        ready.completeExceptionally(IllegalStateException("DSH exited before readiness (exit=$exitCode)"))
                        if (RuntimeStateStore.state.value.phase !in setOf(Stopping, Ready)) {
                            RuntimeStateStore.set(failureState(IllegalStateException("DSH exited with code $exitCode")))
                        }
                    },
                )
                val port = withTimeout(90_000) { ready.await() }
                val authenticatedUrl = "http://127.0.0.1:$port/?token=$token"
                RuntimeUiState(
                    phase = Running,
                    runtimeVersion = manifest.runtimeVersion,
                    detail = RuntimeMessage(RuntimeMessageKind.Running),
                    webUrl = authenticatedUrl,
                    logTail = RuntimeStateStore.state.value.logTail,
                    runtimeMode = mode,
                    rememberRuntimeMode = preferences.getBoolean(PREFERENCE_REMEMBER_MODE, false),
                    rootAccess = rootState,
                )
            } catch (error: Throwable) {
                supervisor.stop()
                throw error
            }
        }.getOrElse(::failureState).also(RuntimeStateStore::set)
    }

    suspend fun stop() = operationMutex.withLock {
        val version = RuntimeStateStore.state.value.runtimeVersion
        RuntimeStateStore.set(
            RuntimeStateStore.state.value.copy(
                phase = Stopping,
                detail = RuntimeMessage(RuntimeMessageKind.Stopping),
            ),
        )
        runCatching { supervisor.stop() }
            .fold(
                onSuccess = {
                    RuntimeStateStore.set(
                        RuntimeUiState(
                            phase = Ready,
                            runtimeVersion = version,
                            detail = RuntimeMessage(RuntimeMessageKind.Stopped),
                            runtimeMode = selectedMode(),
                            rememberRuntimeMode = preferences.getBoolean(PREFERENCE_REMEMBER_MODE, false),
                            rootAccess = rootStateFor(selectedMode()),
                        ),
                    )
                },
                onFailure = { RuntimeStateStore.set(failureState(it)) },
            )
    }

    private fun failureState(error: Throwable): RuntimeUiState {
        Log.e(LOG_TAG, "Runtime operation failed", error)
        if (error is RootAccessDeniedException) {
            return RuntimeStateStore.state.value.copy(
                phase = Ready,
                detail = RuntimeMessage(RuntimeMessageKind.RuntimeReady),
                progress = null,
                webUrl = null,
                rootAccess = Denied,
            )
        }
        return RuntimeUiState(
            phase = Failed,
            runtimeVersion = RuntimeStateStore.state.value.runtimeVersion,
            detail = RuntimeMessage(RuntimeMessageKind.Failed),
            logTail = RuntimeStateStore.state.value.logTail,
            runtimeMode = selectedMode(),
            rememberRuntimeMode = preferences.getBoolean(PREFERENCE_REMEMBER_MODE, false),
            rootAccess = if (
                selectedMode() == RuntimeMode.Chroot && RuntimeStateStore.state.value.rootAccess == Checking
            ) Denied else rootStateFor(selectedMode()),
        )
    }

    private fun selectedMode(): RuntimeMode = sessionMode

    private fun rootStateFor(mode: RuntimeMode): RootAccessState = when (mode) {
        RuntimeMode.Proot, RuntimeMode.Proroot -> NotRequired
        RuntimeMode.Chroot -> RuntimeStateStore.state.value
            .takeIf { it.runtimeMode == RuntimeMode.Chroot && it.rootAccess == Granted }
            ?.rootAccess
            ?: Required
    }

    companion object {
        @Volatile
        private var instance: RuntimeManager? = null

        fun get(context: Context): RuntimeManager = instance ?: synchronized(this) {
            instance ?: RuntimeManager(context.applicationContext).also { instance = it }
        }
    }
}

private class RootAccessDeniedException : IllegalStateException("Root access is required for chroot mode")

private const val MAX_UI_LOG_LINES = 80
private const val LOG_TAG = "RuntimeManager"
private const val PREFERENCES_NAME = "runtime-settings"
private const val PREFERENCE_RUNTIME_MODE = "runtime-mode"
private const val PREFERENCE_REMEMBER_MODE = "remember-runtime-mode"
