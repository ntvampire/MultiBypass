package com.multibypass.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import com.multibypass.app.core.byedpi.ByeDpiController
import com.multibypass.app.core.byedpi.PresetStrategies
import com.multibypass.app.core.byedpi.StrategyTester
import com.multibypass.app.core.vpn.MultiBypassVpnService
import com.multibypass.app.data.model.AntiDpiGroupConfig
import com.multibypass.app.data.model.StrategyTestResult
import com.multibypass.app.data.model.VpnStatus
import com.multibypass.app.data.repository.SettingsRepository
import com.multibypass.app.ui.theme.AccentOrange
import com.multibypass.app.ui.theme.DarkBackground
import com.multibypass.app.ui.theme.DarkSurface
import com.multibypass.app.ui.theme.GreenPrimary
import com.multibypass.app.ui.theme.RedDanger
import com.multibypass.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AntiDpiGroupScreen(
    onNavigateBack: () -> Unit,
    onNavigateToAutoStrategy: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { SettingsRepository.getInstance(context) }
    val antiDpiConfig by repository.antiDpiConfig.collectAsState()
    val lastResults by repository.lastStrategyResults.collectAsState()
    val lastTimestamp by repository.lastStrategyTimestamp.collectAsState()
    val strategyTester = remember { StrategyTester.getInstance(context) }
    val isTesting by strategyTester.isTesting.collectAsState()

    val formattedDate = remember(lastTimestamp) {
        if (lastTimestamp > 0) {
            val sdf = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
            sdf.format(java.util.Date(lastTimestamp))
        } else ""
    }

    BackHandler(onBack = onNavigateBack)

    var newDomainInput by remember { mutableStateOf("") }
    var showStrategyDialog by remember { mutableStateOf(false) }

    var presetStrategies by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(showStrategyDialog) {
        if (showStrategyDialog) {
            presetStrategies = PresetStrategies.loadStrategies(context)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Десинхронизация TCP", fontWeight = FontWeight.Bold) },
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

            // Strategy Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Текущая стратегия ByeByeDPI:", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = antiDpiConfig.strategy,
                        onValueChange = { repository.updateAntiDpiConfig(antiDpiConfig.copy(strategy = it)) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { showStrategyDialog = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Пресеты")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = onNavigateToAutoStrategy,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentOrange)
                        ) {
                            Icon(Icons.Default.AutoMode, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Автоподбор")
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "💡 Автоподбор поочередно тестирует параметры десинхронизации на YouTube, Instagram и X (Twitter) для поиска оптимального соединения.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            if (isTesting) {
                Spacer(modifier = Modifier.height(14.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = AccentOrange,
                                    strokeWidth = 2.5.dp
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Идет автоподбор в фоне...",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            TextButton(onClick = onNavigateToAutoStrategy) {
                                Text("Открыть", color = AccentOrange, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Процесс продолжается даже при выключенном экране. Вы можете продолжить пользоваться телефоном.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            } else if (lastResults.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                val best = lastResults.filter { it.isWorking }.minWithOrNull(
                    compareByDescending<StrategyTestResult> { it.successfulSitesCount }.thenBy { it.latencyMs }
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Результаты автоподбора",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (formattedDate.isNotEmpty()) {
                                    Text(
                                        text = "Проверено: $formattedDate",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )
                                }
                            }
                            TextButton(onClick = onNavigateToAutoStrategy) {
                                Text("Все пресеты (${lastResults.size})", color = AccentOrange)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        if (best != null) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = DarkBackground,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Лучший пресет: ${best.latencyMs} мс",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = GreenPrimary
                                        )
                                        Text(
                                            text = "${best.successfulSitesCount}/${best.testedSitesCount} сервисов",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = GreenPrimary
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = best.strategy,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )

                                    if (best.workingServices.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            best.workingServices.forEach { sName ->
                                                val sLat = best.serviceLatencies[sName]?.let { " ${it}мс" } ?: ""
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = GreenPrimary.copy(alpha = 0.15f)
                                                ) {
                                                    Text(
                                                        text = "$sName$sLat",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = GreenPrimary,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            val isCurrentStrategy = antiDpiConfig.strategy == best.strategy

                            Button(
                                onClick = {
                                    repository.updateAntiDpiConfig(antiDpiConfig.copy(strategy = best.strategy))
                                    if (MultiBypassVpnService.vpnStatus.value == VpnStatus.CONNECTED) {
                                        scope.launch {
                                            ByeDpiController.start(strategy = best.strategy)
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isCurrentStrategy) DarkBackground else GreenPrimary
                                ),
                                enabled = !isCurrentStrategy
                            ) {
                                Icon(
                                    if (isCurrentStrategy) Icons.Default.Check else Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = if (isCurrentStrategy) TextSecondary else Color.White
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isCurrentStrategy) "Активна лучшая стратегия" else "Применить лучшую стратегию",
                                    color = if (isCurrentStrategy) TextSecondary else Color.White
                                )
                            }
                        } else {
                            Text(
                                text = "Ни одна стратегия не показала доступность при последней проверке.",
                                style = MaterialTheme.typography.bodySmall,
                                color = RedDanger
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = onNavigateToAutoStrategy,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Повторить проверку")
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Domains list
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Адреса и домены (${antiDpiConfig.domains.size}):",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(
                    onClick = {
                        repository.updateAntiDpiConfig(antiDpiConfig.copy(domains = AntiDpiGroupConfig().domains))
                    }
                ) {
                    Text("Сброс")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newDomainInput,
                    onValueChange = { newDomainInput = it },
                    placeholder = { Text("Добавить домен (напр. youtube.com)") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        val trimmed = newDomainInput.trim().lowercase()
                        if (trimmed.isNotBlank() && !antiDpiConfig.domains.contains(trimmed)) {
                            repository.updateAntiDpiConfig(antiDpiConfig.copy(domains = antiDpiConfig.domains + trimmed))
                            newDomainInput = ""
                        }
                    },
                    modifier = Modifier.size(50.dp)
                ) {
                    Icon(Icons.Default.AddCircle, contentDescription = "Добавить", tint = AccentOrange, modifier = Modifier.size(36.dp))
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            antiDpiConfig.domains.forEach { domain ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(domain, style = MaterialTheme.typography.bodyMedium)
                        IconButton(
                            onClick = {
                                repository.updateAntiDpiConfig(antiDpiConfig.copy(domains = antiDpiConfig.domains - domain))
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Удалить", tint = TextSecondary)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(30.dp))
        }
    }

    // Strategy Picker Dialog
    if (showStrategyDialog) {
        AlertDialog(
            onDismissRequest = { showStrategyDialog = false },
            title = { Text("Выберите пресет стратегии") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    presetStrategies.forEach { strat ->
                        Text(
                            text = strat,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    repository.updateAntiDpiConfig(antiDpiConfig.copy(strategy = strat))
                                    if (MultiBypassVpnService.vpnStatus.value == VpnStatus.CONNECTED) {
                                        scope.launch {
                                            ByeDpiController.start(strategy = strat)
                                        }
                                    }
                                    showStrategyDialog = false
                                }
                                .padding(vertical = 10.dp)
                        )
                        Divider(color = DarkSurface)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showStrategyDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }
}
