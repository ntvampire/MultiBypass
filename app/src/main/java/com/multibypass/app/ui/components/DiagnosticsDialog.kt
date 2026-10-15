package com.multibypass.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.multibypass.app.core.diagnostics.DiagnosticStatus
import com.multibypass.app.core.diagnostics.DiagnosticStepResult
import com.multibypass.app.core.diagnostics.DiagnosticsReport
import com.multibypass.app.core.diagnostics.NetworkDiagnostics
import com.multibypass.app.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun DiagnosticsDialog(
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isRunning by remember { mutableStateOf(true) }
    var report by remember { mutableStateOf<DiagnosticsReport?>(null) }

    fun startDiagnostics() {
        isRunning = true
        scope.launch {
            report = NetworkDiagnostics.runDiagnostics(context)
            isRunning = false
        }
    }

    LaunchedEffect(Unit) {
        startDiagnostics()
    }

    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = DarkSurface,
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.HealthAndSafety,
                        contentDescription = null,
                        tint = GreenPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Сетевой доктор",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Экспресс-диагностика компонентов",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (isRunning) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp)
                    ) {
                        CircularProgressIndicator(
                            color = GreenPrimary,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Проверка сокетов, DoH и ByeDPI...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                } else if (report != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        report!!.steps.forEach { step ->
                            DiagnosticStepItem(step = step)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Summary card
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (report!!.allOk) GreenPrimary.copy(alpha = 0.12f) else AccentOrange.copy(alpha = 0.12f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Icon(
                                if (report!!.allOk) Icons.Default.CheckCircle else Icons.Default.Info,
                                contentDescription = null,
                                tint = if (report!!.allOk) GreenPrimary else AccentOrange,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (report!!.allOk) {
                                    "Все службы работают штатно"
                                } else {
                                    "Обнаружены предупреждения или сбои"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (report!!.allOk) GreenPrimary else AccentOrange
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            report?.let { r ->
                                val text = r.toPlainText(context)
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("MultiBypass Doctor", text))
                                Toast.makeText(context, "Отчет скопирован в буфер", Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = !isRunning && report != null,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Отчет", fontSize = 12.sp)
                    }

                    Button(
                        onClick = {
                            if (isRunning) onDismissRequest() else startDiagnostics()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (isRunning) CardBorder else GreenPrimary)
                    ) {
                        Text(if (isRunning) "Отмена" else "Повторить", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticStepItem(step: DiagnosticStepResult) {
    val icon = when (step.status) {
        DiagnosticStatus.SUCCESS -> Icons.Default.CheckCircle
        DiagnosticStatus.WARNING -> Icons.Default.Warning
        DiagnosticStatus.ERROR -> Icons.Default.Cancel
        DiagnosticStatus.SKIPPED -> Icons.Default.RemoveCircleOutline
    }
    val tint = when (step.status) {
        DiagnosticStatus.SUCCESS -> GreenPrimary
        DiagnosticStatus.WARNING -> AccentOrange
        DiagnosticStatus.ERROR -> RedDanger
        DiagnosticStatus.SKIPPED -> TextSecondary
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .size(20.dp)
                .padding(top = 2.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = step.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (step.latencyMs != null) {
                    Text(
                        text = "${step.latencyMs} мс",
                        style = MaterialTheme.typography.labelSmall,
                        color = GreenPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Text(
                text = step.description,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            if (!step.detail.isNullOrBlank()) {
                Text(
                    text = step.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = tint.copy(alpha = 0.85f),
                    fontSize = 11.sp
                )
            }
        }
    }
}
