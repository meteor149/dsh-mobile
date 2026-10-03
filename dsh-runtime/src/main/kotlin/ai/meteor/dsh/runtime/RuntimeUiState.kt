package ai.meteor.dsh.runtime

import ai.meteor.ubuntu.runtime.*

enum class RuntimePhase {
    Unavailable,
    NotInstalled,
    Installing,
    Ready,
    Starting,
    Running,
    Stopping,
    Failed,
}

data class RuntimeUiState(
    val phase: RuntimePhase = RuntimePhase.Unavailable,
    val runtimeVersion: String = "",
    val detail: RuntimeMessage = RuntimeMessage(RuntimeMessageKind.ArtifactsUnavailable),
    val progress: Float? = null,
    val webUrl: String? = null,
    val logTail: List<String> = emptyList(),
    val runtimeMode: RuntimeMode = RuntimeMode.Proot,
    val rootAccess: RootAccessState = RootAccessState.NotRequired,
) {
    val isBusy: Boolean
        get() = phase in setOf(
            RuntimePhase.Installing,
            RuntimePhase.Starting,
            RuntimePhase.Stopping,
        )
}
