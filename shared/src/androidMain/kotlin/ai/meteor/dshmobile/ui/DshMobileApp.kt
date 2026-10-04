package ai.meteor.dshmobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas as DrawingCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import ai.meteor.dshmobile.resources.*
import ai.meteor.ubuntu.runtime.RuntimeMessage
import ai.meteor.ubuntu.runtime.RuntimeMessageKind
import ai.meteor.ubuntu.runtime.RootAccessState
import ai.meteor.dsh.runtime.RuntimeMode
import ai.meteor.dsh.runtime.RuntimePhase
import ai.meteor.dsh.runtime.RuntimeUiState
import org.jetbrains.compose.resources.stringResource

@Composable
fun DshMobileApp(
    state: RuntimeUiState,
    onInstall: () -> Unit,
    onStart: () -> Unit,
    onOpen: () -> Unit,
    onStop: () -> Unit,
    onModeChange: (RuntimeMode) -> Unit,
    onRememberModeChange: (Boolean) -> Unit,
    onRequestRoot: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxSize(), color = Canvas) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("DSH", fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.4).sp)
                Text(" / Mobile", color = SecondaryInk, fontSize = 13.sp, lineHeight = 20.sp)
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 440.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp)) {
                    Surface(color = Color.White, shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.dp, Hairline)) {
                        Column(Modifier.fillMaxWidth().padding(24.dp)) {
                            EnvironmentMark(state.runtimeVersion)
                            Spacer(Modifier.height(24.dp))
                            Text(titleFor(state.phase), fontSize = 25.sp, lineHeight = 32.sp,
                                fontWeight = FontWeight.Medium, letterSpacing = (-.7).sp)
                            Spacer(Modifier.height(8.dp))
                            Text(detailFor(state.detail, state.runtimeMode), color = SecondaryInk,
                                fontSize = 13.sp, lineHeight = 20.sp)
                            if (state.isBusy) {
                                Spacer(Modifier.height(20.dp))
                                val progress = state.progress
                                if (progress == null) LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape), color = Ink, trackColor = Layer)
                                else {
                                    LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape), color = Ink, trackColor = Layer)
                                    Text("${(progress.coerceIn(0f, 1f) * 100).toInt()}%", modifier = Modifier.align(Alignment.End).padding(top = 6.dp),
                                        color = SecondaryInk, fontSize = 11.sp, lineHeight = 16.sp)
                                }
                            }
                            Spacer(Modifier.height(28.dp))
                            HorizontalDivider(color = Hairline)
                            ModePicker(state, onModeChange)
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(state.rememberRuntimeMode,
                                enabled = !state.isBusy, role = Role.Checkbox, onValueChange = onRememberModeChange)
                                .padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(state.rememberRuntimeMode, onCheckedChange = null, enabled = !state.isBusy,
                                    modifier = Modifier.size(24.dp),
                                    colors = CheckboxDefaults.colors(checkedColor = Ink))
                                Spacer(Modifier.width(10.dp))
                                Text(stringResource(Res.string.setup_remember_mode), color = SecondaryInk,
                                    fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
                            }
                            if (state.runtimeMode == RuntimeMode.Proroot) {
                                Text(stringResource(Res.string.setup_closed_source_note), color = SecondaryInk,
                                    fontSize = 11.sp, lineHeight = 17.sp, modifier = Modifier.padding(bottom = 12.dp))
                            }
                            if (state.runtimeMode == RuntimeMode.Chroot) {
                                Text(rootAccessLabel(state.rootAccess), color = SecondaryInk,
                                    fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(bottom = 14.dp))
                            }
                            PrimaryAction(state, onInstall, onStart, onOpen, onStop, onRequestRoot)
                            if (state.phase == RuntimePhase.Failed && state.logTail.isNotEmpty()) {
                                var showErrorDetails by remember { mutableStateOf(false) }
                                Spacer(Modifier.height(16.dp))
                                state.logTail.takeLast(3).forEach {
                                    Text(it, color = SecondaryInk, fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp, lineHeight = 16.sp)
                                }
                                TextButton(onClick = { showErrorDetails = true }) {
                                    Text(stringResource(Res.string.error_details))
                                }
                                if (showErrorDetails) AlertDialog(
                                    onDismissRequest = { showErrorDetails = false },
                                    title = { Text(stringResource(Res.string.error_details)) },
                                    text = {
                                        SelectionContainer {
                                            Text(state.logTail.joinToString("\n"), fontFamily = FontFamily.Monospace,
                                                fontSize = 11.sp, lineHeight = 17.sp,
                                                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()))
                                        }
                                    },
                                    confirmButton = { TextButton(onClick = { showErrorDetails = false }) {
                                        Text(stringResource(Res.string.error_details_close))
                                    } },
                                    dismissButton = {
                                        val clipboard = LocalClipboardManager.current
                                        TextButton(onClick = { clipboard.setText(AnnotatedString(state.logTail.joinToString("\n"))) }) {
                                            Text(stringResource(Res.string.error_details_copy))
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EnvironmentMark(version: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).background(DeepSeekBlueSoft, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
            DrawingCanvas(Modifier.size(22.dp)) {
                val stroke = 1.6.dp.toPx()
                drawRoundRect(DeepSeekBlue, cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()), style = Stroke(stroke))
                drawLine(DeepSeekBlue, Offset(size.width * .24f, size.height * .33f), Offset(size.width * .41f, size.height * .5f), stroke, StrokeCap.Round)
                drawLine(DeepSeekBlue, Offset(size.width * .41f, size.height * .5f), Offset(size.width * .24f, size.height * .67f), stroke, StrokeCap.Round)
                drawLine(DeepSeekBlue, Offset(size.width * .54f, size.height * .67f), Offset(size.width * .76f, size.height * .67f), stroke, StrokeCap.Round)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text("Ubuntu", fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium)
            Text(version.ifEmpty { "24.04 LTS" }, color = SecondaryInk, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun ModePicker(state: RuntimeUiState, onModeChange: (RuntimeMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val enabled = !state.isBusy && state.phase != RuntimePhase.Running && state.rootAccess != RootAccessState.Checking
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(Res.string.runtime_mode_title), color = SecondaryInk,
            fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { open = true }, enabled = enabled,
                border = BorderStroke(1.dp, Ink.copy(alpha = .22f)),
                modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = Layer, contentColor = Ink, disabledContentColor = SecondaryInk)) {
                Text(modeName(state.runtimeMode), fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(10.dp))
                DrawingCanvas(Modifier.size(10.dp, 6.dp)) {
                    val stroke = 1.4.dp.toPx()
                    drawLine(Ink, Offset.Zero, Offset(size.width / 2, size.height), stroke, StrokeCap.Round)
                    drawLine(Ink, Offset(size.width / 2, size.height), Offset(size.width, 0f), stroke, StrokeCap.Round)
                }
            }
            DropdownMenu(expanded = open && enabled, onDismissRequest = { open = false },
                containerColor = Color.White, shape = RoundedCornerShape(16.dp), tonalElevation = 0.dp,
                shadowElevation = 8.dp) {
                RuntimeMode.entries.forEach { mode ->
                    DropdownMenuItem(text = {
                        Column {
                            Text(modeName(mode), fontSize = 14.sp, lineHeight = 20.sp,
                                fontWeight = if (mode == state.runtimeMode) FontWeight.SemiBold else FontWeight.Normal)
                            Text(stringResource(when (mode) {
                                RuntimeMode.Proot -> Res.string.setup_proot_summary
                                RuntimeMode.Proroot -> Res.string.setup_proroot_summary
                                RuntimeMode.Chroot -> Res.string.setup_chroot_summary
                            }), color = SecondaryInk, fontSize = 11.sp, lineHeight = 17.sp)
                        }
                    }, onClick = { onModeChange(mode); open = false },
                        trailingIcon = { if (state.runtimeMode == mode) Text("✓", fontSize = 14.sp) },
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp))
                }
            }
        }
    }
}

private fun modeName(mode: RuntimeMode) = when (mode) {
    RuntimeMode.Proot -> "PRoot"
    RuntimeMode.Proroot -> "proroot"
    RuntimeMode.Chroot -> "chroot"
}

@Composable
private fun PrimaryAction(state: RuntimeUiState, onInstall: () -> Unit, onStart: () -> Unit,
    onOpen: () -> Unit, onStop: () -> Unit, onRequestRoot: () -> Unit) {
    val needsRoot = state.phase == RuntimePhase.Ready && state.runtimeMode == RuntimeMode.Chroot &&
        state.rootAccess != RootAccessState.Granted
    val action = when {
        needsRoot -> onRequestRoot
        state.phase in setOf(RuntimePhase.NotInstalled, RuntimePhase.Failed) -> onInstall
        state.phase == RuntimePhase.Ready -> onStart
        state.phase == RuntimePhase.Running -> onOpen
        else -> ({})
    }
    val label = when {
        needsRoot -> if (state.rootAccess == RootAccessState.Denied) Res.string.action_retry_root else Res.string.action_request_root
        state.phase in setOf(RuntimePhase.NotInstalled, RuntimePhase.Failed) -> Res.string.action_install
        state.phase == RuntimePhase.Ready -> Res.string.action_start
        state.phase == RuntimePhase.Running -> Res.string.action_open
        state.isBusy -> Res.string.action_processing
        else -> Res.string.action_waiting
    }
    Button(onClick = action, enabled = !state.isBusy && state.phase != RuntimePhase.Unavailable && state.rootAccess != RootAccessState.Checking,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Ink, disabledContainerColor = Layer, disabledContentColor = SecondaryInk)) {
        Text(stringResource(label), fontSize = 14.sp, lineHeight = 20.sp,
            fontWeight = FontWeight.Medium, modifier = Modifier.padding(vertical = 4.dp))
    }
    if (state.phase == RuntimePhase.Running) {
        TextButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.action_stop), color = SecondaryInk, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun rootAccessLabel(state: RootAccessState): String = when (state) {
    RootAccessState.NotRequired -> stringResource(Res.string.root_not_required)
    RootAccessState.Required -> stringResource(Res.string.root_required)
    RootAccessState.Checking -> stringResource(Res.string.root_checking)
    RootAccessState.Granted -> stringResource(Res.string.root_granted)
    RootAccessState.Denied -> stringResource(Res.string.root_denied)
}

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
private fun detailFor(message: RuntimeMessage, runtimeMode: RuntimeMode): String = when (message.kind) {
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
    RuntimeMessageKind.Starting -> stringResource(
        when (runtimeMode) {
            RuntimeMode.Proot -> Res.string.detail_starting
            RuntimeMode.Proroot -> Res.string.detail_starting_proroot
            RuntimeMode.Chroot -> Res.string.detail_starting_chroot
        },
    )
    RuntimeMessageKind.Running -> stringResource(Res.string.detail_running)
    RuntimeMessageKind.Stopping -> stringResource(Res.string.detail_stopping)
    RuntimeMessageKind.Stopped -> stringResource(Res.string.detail_stopped)
    RuntimeMessageKind.Failed -> stringResource(Res.string.detail_failed)
}
