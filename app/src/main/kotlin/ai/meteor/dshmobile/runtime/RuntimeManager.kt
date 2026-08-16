package ai.meteor.dshmobile.runtime

import android.content.Context
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
                    detail = "APK 中没有 runtime 制品。运行 buildRuntime 后重新构建应用。",
                )
                installer.probe(manifest) != null -> RuntimeUiState(
                    phase = Ready,
                    runtimeVersion = manifest.runtimeVersion,
                    detail = "Ubuntu 和 DeepSeek Harness 已安装，可以启动本地服务。",
                )
                else -> RuntimeUiState(
                    phase = NotInstalled,
                    runtimeVersion = manifest.runtimeVersion,
                    detail = "已验证 APK 内的 runtime 清单，等待首次安装。",
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
                    detail = "正在校验并展开版本化 Ubuntu rootfs。",
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
                detail = "Ubuntu 和 DeepSeek Harness 已安装，可以启动本地服务。",
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
                    detail = "正在启动 PRoot、Ubuntu 和认证网关。",
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
                detail = "认证后的 Web UI 仅通过本机回环地址提供。",
                webUrl = session.authenticatedUrl,
                logTail = RuntimeStateStore.state.value.logTail,
            )
        }.getOrElse(::failureState).also(RuntimeStateStore::set)
    }

    suspend fun stop() = operationMutex.withLock {
        val version = RuntimeStateStore.state.value.runtimeVersion
        RuntimeStateStore.set(RuntimeStateStore.state.value.copy(phase = Stopping, detail = "正在停止全部子进程。"))
        runCatching { supervisor.stop() }
            .fold(
                onSuccess = {
                    RuntimeStateStore.set(
                        RuntimeUiState(
                            phase = Ready,
                            runtimeVersion = version,
                            detail = "运行时已停止，Ubuntu 数据仍保留在应用私有目录。",
                        ),
                    )
                },
                onFailure = { RuntimeStateStore.set(failureState(it)) },
            )
    }

    private fun failureState(error: Throwable): RuntimeUiState = RuntimeUiState(
        phase = Failed,
        runtimeVersion = RuntimeStateStore.state.value.runtimeVersion,
        detail = error.message ?: error::class.simpleName ?: "Unknown runtime error",
        logTail = RuntimeStateStore.state.value.logTail,
    )

    companion object {
        @Volatile
        private var instance: RuntimeManager? = null

        fun get(context: Context): RuntimeManager = instance ?: synchronized(this) {
            instance ?: RuntimeManager(context.applicationContext).also { instance = it }
        }
    }
}

private const val MAX_UI_LOG_LINES = 80
