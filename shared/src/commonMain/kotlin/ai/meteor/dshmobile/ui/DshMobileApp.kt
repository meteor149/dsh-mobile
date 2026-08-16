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
import ai.meteor.dshmobile.runtime.RuntimePhase
import ai.meteor.dshmobile.runtime.RuntimeUiState

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
                    text = "DEEPSEEK HARNESS  ·  MOBILE",
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
                    text = state.detail,
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
                    text = "运行环境与项目文件均保存在应用私有目录。",
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
                number = "1",
                title = "安装运行时",
                detail = "Ubuntu 24.04 · ARM64",
                state = installStepState(phase),
            )
            HorizontalDivider(color = Hairline)
            StepRow(
                number = "2",
                title = "启动本地服务",
                detail = "PRoot · DSH gateway",
                state = startStepState(phase),
            )
            HorizontalDivider(color = Hairline)
            StepRow(
                number = "3",
                title = "打开 Web UI",
                detail = "仅限本机回环地址",
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
                text = if (state == StepState.Complete) "✓" else number,
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
                    text = "RUNTIME",
                    color = CaptionInk,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp,
                )
                PhaseChip(state.phase)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = state.runtimeVersion,
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
        ) { Text("安装运行时") }

        RuntimePhase.Ready -> Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(26.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Ink),
        ) { Text("启动 DeepSeek Harness") }

        RuntimePhase.Running -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onOpen,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Ink),
            ) { Text("打开 DeepSeek Harness") }
            OutlinedButton(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, Hairline),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = SecondaryInk),
            ) { Text("停止运行时") }
        }

        else -> Button(
            onClick = {},
            enabled = false,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(26.dp),
        ) { Text(if (phase.isBusyPhase()) "处理中…" else "等待运行时制品") }
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

private fun stepLabel(state: StepState): String = when (state) {
    StepState.Complete -> "DONE"
    StepState.Active -> "CURRENT"
    StepState.Pending -> "NEXT"
    StepState.Error -> "CHECK"
}

private fun RuntimePhase.isBusyPhase(): Boolean = this in setOf(
    RuntimePhase.Installing,
    RuntimePhase.Starting,
    RuntimePhase.Stopping,
)

private fun titleFor(phase: RuntimePhase): String = when (phase) {
    RuntimePhase.Unavailable -> "运行时尚未打包"
    RuntimePhase.NotInstalled -> "准备好后，再开始安装"
    RuntimePhase.Installing -> "正在安装本地运行时"
    RuntimePhase.Ready -> "运行时已安装"
    RuntimePhase.Starting -> "正在启动 Harness"
    RuntimePhase.Running -> "DeepSeek Harness 已就绪"
    RuntimePhase.Stopping -> "正在停止本地服务"
    RuntimePhase.Failed -> "运行时需要处理"
}

private fun phaseLabel(phase: RuntimePhase): String = when (phase) {
    RuntimePhase.Unavailable -> "NO ARTIFACT"
    RuntimePhase.NotInstalled -> "AVAILABLE"
    RuntimePhase.Installing -> "INSTALLING"
    RuntimePhase.Ready -> "READY"
    RuntimePhase.Starting -> "BOOTING"
    RuntimePhase.Running -> "ONLINE"
    RuntimePhase.Stopping -> "STOPPING"
    RuntimePhase.Failed -> "FAILED"
}
