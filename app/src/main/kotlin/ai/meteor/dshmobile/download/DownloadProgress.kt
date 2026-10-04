package ai.meteor.dshmobile.download

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ai.meteor.dshmobile.R

@Composable
fun DownloadProgress(state: DownloadState, onCancel: () -> Unit) {
    if (!state.active) return
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.download_saving)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(state.filename)
                val total = state.total
                if (total != null && total > 0) {
                    LinearProgressIndicator(progress = { (state.received.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text("${(state.received * 100 / total).coerceIn(0, 100)}%")
                } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.download_cancel)) } },
    )
}
