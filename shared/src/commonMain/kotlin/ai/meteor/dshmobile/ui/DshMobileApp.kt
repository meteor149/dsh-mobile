package ai.meteor.dshmobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.meteor.dshmobile.resources.*
import ai.meteor.dshmobile.runtime.RuntimeMessage
import ai.meteor.dshmobile.runtime.RuntimeMessageKind
import ai.meteor.dshmobile.runtime.RuntimePhase
import ai.meteor.dshmobile.runtime.RuntimeUiState
import org.jetbrains.compose.resources.stringResource

@Composable
fun DshMobileApp(
    state: RuntimeUiState,
    onInstall: () -> Unit,
    onStart: () -> Unit,
    onOpen: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier.background(
                Brush.radialGradient(
                    colors = listOf(DeepSeekBlue.copy(alpha = 0.10f), Color.Transparent),
                    radius = 760f,
                ),
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 24.dp),
            ) {
                Text(
                    text = stringResource(Res.string.brand_line),
                    color = DeepSeekBlue,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.1.sp,
                )
                Spacer(Modifier.height(22.dp))
                Text(
                    text = titleFor(state.phase),
                    style = MaterialTheme.typography.headlineLarge,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = detailFor(state.detail),
                    color = SecondaryInk,
                    style = MaterialTheme.typography.bodyLarge,
                )

                Spacer(Modifier.height(28.dp))
                SetupSteps(state.phase)
                Spacer(Modifier.height(18.dp))
                RuntimeCard(state)
                Spacer(Modifier.height(18.dp))
                RuntimeActions(
                    phase = state.phase,
                    onInstall = onInstall,
                    onStart = onStart,
                    onOpen = onOpen,
                    onStop = onStop,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(Res.string.private_data_note),
                    modifier = Modifier.fillMaxWidth(),
                    color = CaptionInk,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            }
        }
    }
}

@Composable
private fun SetupSteps(phase: RuntimePhase) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = Canvas.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, Hairline),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
            StepRow(
                number = stringResource(Res.string.step_number_install),
                title = stringResource(Res.string.step_install_title),
                detail = stringResource(Res.string.step_install_detail),
                state = installStepState(phase),
            )
            HorizontalDivider(color = Hairline)
            StepRow(
                number = stringResource(Res.string.step_number_start),
                title = stringResource(Res.string.step_start_title),
                detail = stringResource(Res.string.step_start_detail),
                state = startStepState(phase),
            )
            HorizontalDivider(color = Hairline)
            StepRow(
                number = stringResource(Res.string.step_number_web),
                title = stringResource(Res.string.step_web_title),
                detail = stringResource(Res.string.step_web_detail),
                state = webStepState(phase),
            )
        }
    }
}

@Composable
private fun StepRow(
    number: String,
    title: String,
    detail: String,
    state: StepState,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val accent = when (state) {
            StepState.Complete -> Success
            StepState.Active -> DeepSeekBlue
            StepState.Error -> MaterialTheme.colorScheme.error
            StepState.Pending -> CaptionInk
        }
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(
                    color = if (state == StepState.Pending) Layer else accent.copy(alpha = 0.12f),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (state == StepState.Complete) {
                    stringResource(Res.string.step_complete_symbol)
                } else {
                    number
                },
                color = accent,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, color = CaptionInk, fontSize = 12.sp, lineHeight = 18.sp)
        }
        Text(
            text = stepLabel(state),
            color = accent,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.5.sp,
        )
    }
}

@Composable
private fun RuntimeCard(state: RuntimeUiState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = Layer,
        border = BorderStroke(1.dp, Hairline),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.runtime_section),
                    color = CaptionInk,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp,
                )
                PhaseChip(state.phase)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = state.runtimeVersion.ifEmpty { stringResource(Res.string.runtime_version_unavailable) },
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = Ink,
            )
            state.progress?.let { progress ->
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(99.dp)),
                    color = DeepSeekBlue,
                    trackColor = DeepSeekBlueSoft,
                )
            }
            if (state.logTail.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = Hairline)
                Spacer(Modifier.height(12.dp))
                state.logTail.takeLast(4).forEach { line ->
                    Text(
                        text = line,
                        color = SecondaryInk,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun PhaseChip(phase: RuntimePhase) {
    val color = when (phase) {
        RuntimePhase.Running, RuntimePhase.Ready -> Success
        RuntimePhase.Failed -> MaterialTheme.colorScheme.error
        RuntimePhase.Unavailable -> Warning
        else -> DeepSeekBlue
    }
    Text(
        text = phaseLabel(phase),
        modifier = Modifier
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(99.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        color = color,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
    )
}

@Composable
private fun RuntimeActions(
    phase: RuntimePhase,
    onInstall: () -> Unit,
    onStart: () -> Unit,
    onOpen: () -> Unit,
    onStop: () -> Unit,
) {
    when (phase) {
        RuntimePhase.NotInstalled, RuntimePhase.Failed -> Button(
            onClick = onInstall,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(26.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Ink),
        ) { Text(stringResource(Res.string.action_install)) }

        RuntimePhase.Ready -> Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(26.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Ink),
        ) { Text(stringResource(Res.string.action_start)) }

        RuntimePhase.Running -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onOpen,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Ink),
            ) { Text(stringResource(Res.string.action_open)) }
            OutlinedButton(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, Hairline),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = SecondaryInk),
            ) { Text(stringResource(Res.string.action_stop)) }
        }

        else -> Button(
            onClick = {},
            enabled = false,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(26.dp),
        ) {
            Text(
                stringResource(
                    if (phase.isBusyPhase()) Res.string.action_processing else Res.string.action_waiting,
                ),
            )
        }
    }
}

private enum class StepState { Complete, Active, Pending, Error }

private fun installStepState(phase: RuntimePhase): StepState = when (phase) {
    RuntimePhase.NotInstalled, RuntimePhase.Installing -> StepState.Active
    RuntimePhase.Ready, RuntimePhase.Starting, RuntimePhase.Running, RuntimePhase.Stopping -> StepState.Complete
    RuntimePhase.Failed -> StepState.Error
    RuntimePhase.Unavailable -> StepState.Pending
}

private fun startStepState(phase: RuntimePhase): StepState = when (phase) {
    RuntimePhase.Ready, RuntimePhase.Starting, RuntimePhase.Stopping -> StepState.Active
    RuntimePhase.Running -> StepState.Complete
    RuntimePhase.Failed -> StepState.Error
    else -> StepState.Pending
}

private fun webStepState(phase: RuntimePhase): StepState = when (phase) {
    RuntimePhase.Running -> StepState.Active
    RuntimePhase.Failed -> StepState.Error
    else -> StepState.Pending
}

@Composable
private fun stepLabel(state: StepState): String = when (state) {
    StepState.Complete -> stringResource(Res.string.step_state_done)
    StepState.Active -> stringResource(Res.string.step_state_current)
    StepState.Pending -> stringResource(Res.string.step_state_next)
    StepState.Error -> stringResource(Res.string.step_state_check)
}

private fun RuntimePhase.isBusyPhase(): Boolean = this in setOf(
    RuntimePhase.Installing,
    RuntimePhase.Starting,
    RuntimePhase.Stopping,
)

@Composable
private fun titleFor(phase: RuntimePhase): String = when (phase) {
    RuntimePhase.Unavailable -> stringResource(Res.string.title_unavailable)
    RuntimePhase.NotInstalled -> stringResource(Res.string.title_not_installed)
    RuntimePhase.Installing -> stringResource(Res.string.title_installing)
    RuntimePhase.Ready -> stringResource(Res.string.title_ready)
    RuntimePhase.Starting -> stringResource(Res.string.title_starting)
    RuntimePhase.Running -> stringResource(Res.string.title_running)
    RuntimePhase.Stopping -> stringResource(Res.string.title_stopping)
    RuntimePhase.Failed -> stringResource(Res.string.title_failed)
}

@Composable
private fun phaseLabel(phase: RuntimePhase): String = when (phase) {
    RuntimePhase.Unavailable -> stringResource(Res.string.phase_unavailable)
    RuntimePhase.NotInstalled -> stringResource(Res.string.phase_not_installed)
    RuntimePhase.Installing -> stringResource(Res.string.phase_installing)
    RuntimePhase.Ready -> stringResource(Res.string.phase_ready)
    RuntimePhase.Starting -> stringResource(Res.string.phase_starting)
    RuntimePhase.Running -> stringResource(Res.string.phase_running)
    RuntimePhase.Stopping -> stringResource(Res.string.phase_stopping)
    RuntimePhase.Failed -> stringResource(Res.string.phase_failed)
}

@Composable
private fun detailFor(message: RuntimeMessage): String = when (message.kind) {
    RuntimeMessageKind.ArtifactsUnavailable -> stringResource(Res.string.detail_artifacts_unavailable)
    RuntimeMessageKind.RuntimeReady -> stringResource(Res.string.detail_runtime_ready)
    RuntimeMessageKind.RuntimeNotInstalled -> stringResource(Res.string.detail_runtime_not_installed)
    RuntimeMessageKind.Installing -> stringResource(Res.string.detail_installing)
    RuntimeMessageKind.VerifyingRootfs -> stringResource(Res.string.detail_verifying_rootfs)
    RuntimeMessageKind.ExtractingUbuntu -> stringResource(Res.string.detail_extracting_ubuntu)
    RuntimeMessageKind.ExtractingEntries -> stringResource(
        Res.string.detail_extracting_entries,
        requireNotNull(message.count),
    )
    RuntimeMessageKind.InstallComplete -> stringResource(Res.string.detail_install_complete)
    RuntimeMessageKind.Starting -> stringResource(Res.string.detail_starting)
    RuntimeMessageKind.Running -> stringResource(Res.string.detail_running)
    RuntimeMessageKind.Stopping -> stringResource(Res.string.detail_stopping)
    RuntimeMessageKind.Stopped -> stringResource(Res.string.detail_stopped)
    RuntimeMessageKind.Failed -> stringResource(Res.string.detail_failed)
}
