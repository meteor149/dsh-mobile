package ai.meteor.dshmobile.runtime

import android.content.Context
import android.util.Log
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
    private val artifacts = RuntimeArtifactRepository(context)
    private val installer = RootfsInstaller(context, artifacts)
    private val supervisor = RuntimeProcessSupervisor(context)
    private val operationMutex = Mutex()

    suspend fun probe() = operationMutex.withLock {
        if (RuntimeStateStore.state.value.phase in setOf(Running, Starting, Stopping)) return@withLock
        runCatching {
            val manifest = artifacts.readManifest()
            when {
                !manifest.available -> RuntimeUiState(
                    phase = Unavailable,
                    runtimeVersion = manifest.runtimeVersion,
                    detail = RuntimeMessage(RuntimeMessageKind.ArtifactsUnavailable),
                )
                installer.probe(manifest) != null -> RuntimeUiState(
                    phase = Ready,
                    runtimeVersion = manifest.runtimeVersion,
                    detail = RuntimeMessage(RuntimeMessageKind.RuntimeReady),
                )
                else -> RuntimeUiState(
                    phase = NotInstalled,
                    runtimeVersion = manifest.runtimeVersion,
                    detail = RuntimeMessage(RuntimeMessageKind.RuntimeNotInstalled),
                )
            }
        }.getOrElse(::failureState).also(RuntimeStateStore::set)
    }

    suspend fun install() = operationMutex.withLock {
        runCatching {
            val manifest = artifacts.readManifest()
            RuntimeStateStore.set(
                RuntimeUiState(
                    phase = Installing,
                    runtimeVersion = manifest.runtimeVersion,
                    detail = RuntimeMessage(RuntimeMessageKind.Installing),
                    progress = 0f,
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
            )
        }.getOrElse(::failureState).also(RuntimeStateStore::set)
    }

    suspend fun start() = operationMutex.withLock {
        runCatching {
            val manifest = artifacts.readManifest()
            val installed = requireNotNull(installer.probe(manifest)) { "Runtime is not installed" }
            RuntimeStateStore.set(
                RuntimeUiState(
                    phase = Starting,
                    runtimeVersion = manifest.runtimeVersion,
                    detail = RuntimeMessage(RuntimeMessageKind.Starting),
                ),
            )
            val session = supervisor.start(
                runtime = installed,
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
                        ),
                    )
                },
                onFailure = { RuntimeStateStore.set(failureState(it)) },
            )
    }

    private fun failureState(error: Throwable): RuntimeUiState {
        Log.e(LOG_TAG, "Runtime operation failed", error)
        return RuntimeUiState(
            phase = Failed,
            runtimeVersion = RuntimeStateStore.state.value.runtimeVersion,
            detail = RuntimeMessage(RuntimeMessageKind.Failed),
            logTail = RuntimeStateStore.state.value.logTail,
        )
    }

    companion object {
        @Volatile
        private var instance: RuntimeManager? = null

        fun get(context: Context): RuntimeManager = instance ?: synchronized(this) {
            instance ?: RuntimeManager(context.applicationContext).also { instance = it }
        }
    }
}

private const val MAX_UI_LOG_LINES = 80
private const val LOG_TAG = "RuntimeManager"
