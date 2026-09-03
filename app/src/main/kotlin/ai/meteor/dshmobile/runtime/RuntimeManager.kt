package ai.meteor.dshmobile.runtime

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import ai.meteor.dshmobile.runtime.RootAccessState.Checking
import ai.meteor.dshmobile.runtime.RootAccessState.Denied
import ai.meteor.dshmobile.runtime.RootAccessState.Granted
import ai.meteor.dshmobile.runtime.RootAccessState.NotRequired
import ai.meteor.dshmobile.runtime.RootAccessState.Required
import ai.meteor.dshmobile.runtime.RuntimePhase.Failed
import ai.meteor.dshmobile.runtime.RuntimePhase.Installing
import ai.meteor.dshmobile.runtime.RuntimePhase.NotInstalled
import ai.meteor.dshmobile.runtime.RuntimePhase.Ready
import ai.meteor.dshmobile.runtime.RuntimePhase.Running
import ai.meteor.dshmobile.runtime.RuntimePhase.Starting
import ai.meteor.dshmobile.runtime.RuntimePhase.Stopping
import ai.meteor.dshmobile.runtime.RuntimePhase.Unavailable
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
    private val rootAccess = RootAccessController()
    private val supervisor = RuntimeProcessSupervisor(context, rootAccess)
    private val operationMutex = Mutex()
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun selectRuntimeMode(mode: RuntimeMode) {
        val current = RuntimeStateStore.state.value
        if (current.isBusy || current.phase == Running || current.rootAccess == Checking) return
        preferences.edit { putString(PREFERENCE_RUNTIME_MODE, mode.name) }
        RuntimeStateStore.set(
            current.copy(
                runtimeMode = mode,
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
                !manifest.available -> RuntimeUiState(
                    phase = Unavailable,
                    runtimeVersion = manifest.runtimeVersion,
                    detail = RuntimeMessage(RuntimeMessageKind.ArtifactsUnavailable),
                    runtimeMode = mode,
                    rootAccess = rootState,
                )
                installer.probe(manifest) != null -> RuntimeUiState(
                    phase = Ready,
                    runtimeVersion = manifest.runtimeVersion,
                    detail = RuntimeMessage(RuntimeMessageKind.RuntimeReady),
                    runtimeMode = mode,
                    rootAccess = rootState,
                )
                else -> RuntimeUiState(
                    phase = NotInstalled,
                    runtimeVersion = manifest.runtimeVersion,
                    detail = RuntimeMessage(RuntimeMessageKind.RuntimeNotInstalled),
                    runtimeMode = mode,
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
                    rootAccess = rootState,
                ),
            )
            installer.install(manifest) { progress, message ->
                RuntimeStateStore.set(
                    RuntimeStateStore.state.value.copy(
                        phase = Installing,
                        detail = message,
                        progress = progress,
                    ),
                )
            }
            RuntimeUiState(
                phase = Ready,
                runtimeVersion = manifest.runtimeVersion,
                detail = RuntimeMessage(RuntimeMessageKind.RuntimeReady),
                runtimeMode = mode,
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
                    rootAccess = rootState,
                ),
            )
            val session = supervisor.start(
                runtime = installed,
                mode = mode,
                onLog = RuntimeStateStore::appendLog,
                onExit = { exitCode ->
                    if (RuntimeStateStore.state.value.phase !in setOf(Stopping, Ready)) {
                        RuntimeStateStore.set(
                            failureState(IllegalStateException("Runtime process exited with code $exitCode")),
                        )
                    }
                },
            )
            RuntimeUiState(
                phase = Running,
                runtimeVersion = manifest.runtimeVersion,
                detail = RuntimeMessage(RuntimeMessageKind.Running),
                webUrl = session.authenticatedUrl,
                logTail = RuntimeStateStore.state.value.logTail,
                runtimeMode = mode,
                rootAccess = rootState,
            )
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
            rootAccess = if (
                selectedMode() == RuntimeMode.Chroot && RuntimeStateStore.state.value.rootAccess == Checking
            ) Denied else rootStateFor(selectedMode()),
        )
    }

    private fun selectedMode(): RuntimeMode = preferences
        .getString(PREFERENCE_RUNTIME_MODE, RuntimeMode.Proot.name)
        ?.let { saved -> RuntimeMode.entries.firstOrNull { it.name == saved } }
        ?: RuntimeMode.Proot

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
