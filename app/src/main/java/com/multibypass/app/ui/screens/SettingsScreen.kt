package com.multibypass.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.multibypass.app.BuildConfig
import com.multibypass.app.core.updater.AppUpdateManager
import com.multibypass.app.core.updater.UpdateInfo
import com.multibypass.app.data.repository.SettingsRepository
import com.multibypass.app.ui.theme.*
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit
) {
    BackHandler(onBack = onNavigateBack)

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val repository = remember { SettingsRepository.getInstance(context) }
    val scope = rememberCoroutineScope()

    val bootAutoStart by repository.bootAutoStart.collectAsState()
    val downloadProgress by AppUpdateManager.downloadProgress.collectAsState()

    val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }
    var isIgnoringBatteryOptimizations by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
            } else {
                true
            }
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    isIgnoringBatteryOptimizations =
                        powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateStatusMessage by remember { mutableStateOf<String?>(null) }
    var isUpdatingLists by remember { mutableStateOf(false) }
    var listsUpdateStatusMessage by remember { mutableStateOf<String?>(null) }
    var showDiagnosticsDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
            )
        },
        containerColor = DarkBackground
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // Auto-start setting
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Автозапуск при загрузке", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text("Автоматически подключать VPN при включении устройства", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                    Switch(
                        checked = bootAutoStart,
                        onCheckedChange = { repository.setBootAutoStart(it) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))



            // Stability & Background operation
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Стабильность и работа в фоне",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "Работа в фоне без ограничений",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                if (isIgnoringBatteryOptimizations)
                                    "Оптимизация батареи отключена (рекомендуется)"
                                else
                                    "Исключите приложение из оптимизации батареи для предотвращения усыпления фоновых служб",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isIgnoringBatteryOptimizations) GreenPrimary else TextSecondary
                            )

                            if (!isIgnoringBatteryOptimizations) {
                                Spacer(modifier = Modifier.height(10.dp))
                                OutlinedButton(
                                    onClick = {
                                        try {
                                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                                data = Uri.parse("package:${context.packageName}")
                                            }
                                            context.startActivity(intent)
                                        } catch (_: Exception) {
                                            try {
                                                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                                context.startActivity(intent)
                                            } catch (_: Exception) {}
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.BatteryChargingFull, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Отключить ограничения батареи")
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Update section
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Обновления приложения", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Текущая версия: v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    if (isCheckingUpdate) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    } else {
                        Button(
                            onClick = {
                                scope.launch {
                                    isCheckingUpdate = true
                                    val info = AppUpdateManager.checkForUpdates()
                                    isCheckingUpdate = false
                                    if (info != null && info.isNewer) {
                                        updateInfo = info
                                        showUpdateDialog = true
                                    } else {
                                        updateStatusMessage = "У вас установлена актуальная версия"
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Проверить обновления")
                        }
                    }

                    if (updateStatusMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(updateStatusMessage!!, color = GreenPrimary, style = MaterialTheme.typography.bodySmall)
                    }

                    if (downloadProgress != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { (downloadProgress ?: 0) / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Загрузка: $downloadProgress%", style = MaterialTheme.typography.bodySmall, color = AccentBlue)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Lists Update section
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Списки правил и DNS", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Правила DNS, десинхронизации и серверы SmartDNS",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    if (isUpdatingLists) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), color = GreenPrimary)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Загрузка списков...", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        }
                    } else {
                        Button(
                            onClick = {
                                scope.launch {
                                    isUpdatingLists = true
                                    listsUpdateStatusMessage = null
                                    val result = com.multibypass.app.core.lists.ListUpdateManager.forceUpdateAll(context)
                                    isUpdatingLists = false
                                    listsUpdateStatusMessage = if (result.success) {
                                        "Обновлено: DNS (${result.dnsCount}), Anti-DPI (${result.antiDpiCount}), Стратегии (${result.strategiesCount}), Серверы (${result.dnsProvidersCount})"
                                    } else {
                                        result.errorMessage ?: "Ошибка загрузки списков"
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Обновить все списки")
                        }
                    }

                    if (listsUpdateStatusMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            listsUpdateStatusMessage!!,
                            color = if (listsUpdateStatusMessage!!.startsWith("Обновлено")) GreenPrimary else AccentOrange,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Network Doctor Section
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Default.HealthAndSafety,
                            contentDescription = null,
                            tint = GreenPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Сетевой доктор", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Экспресс-диагностика VPN, ByeByeDPI, SmartDNS и прокси",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = { showDiagnosticsDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Запустить диагностику")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // About Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("О приложении", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "MultiBypass — утилита раздельной маршрутизации и обхода сетевых ограничений (SmartDNS, ByeByeDPI, Telegram WebSocket Proxy).",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ntvampire/MultiBypass"))
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Репозиторий на GitHub")
                    }
                }
            }

            Spacer(modifier = Modifier.height(30.dp))
        }
    }

    if (showUpdateDialog && updateInfo != null) {
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            title = { Text("Доступно обновление ${updateInfo!!.tagName}") },
            text = {
                Column {
                    Text(updateInfo!!.releaseNotes, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showUpdateDialog = false
                        AppUpdateManager.downloadAndInstallApk(context, updateInfo!!.downloadUrl)
                    }
                ) {
                    Text("Скачать и установить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUpdateDialog = false }) {
                    Text("Позже")
                }
            }
        )
    }

    if (showDiagnosticsDialog) {
        com.multibypass.app.ui.components.DiagnosticsDialog(
            onDismissRequest = { showDiagnosticsDialog = false }
        )
    }
}
