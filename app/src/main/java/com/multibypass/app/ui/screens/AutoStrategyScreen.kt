package com.multibypass.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.multibypass.app.data.model.VpnStatus
import com.multibypass.app.data.repository.SettingsRepository
import com.multibypass.app.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoStrategyScreen(
    onNavigateBack: () -> Unit
) {
    BackHandler(onBack = onNavigateBack)

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val repository = remember { SettingsRepository.getInstance(context) }
    val antiDpiConfig by repository.antiDpiConfig.collectAsState()

    val tester = remember { StrategyTester.getInstance(context) }
    val isTesting by tester.isTesting.collectAsState()
    val testResults by tester.testResults.collectAsState()
    val lastTimestamp by repository.lastStrategyTimestamp.collectAsState()

    val formattedDate = remember(lastTimestamp) {
        if (lastTimestamp > 0) {
            val sdf = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
            sdf.format(java.util.Date(lastTimestamp))
        } else ""
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Автоподбор стратегий", fontWeight = FontWeight.Bold) },
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
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // Action Header Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Тестирование сетевых стратегий",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Приложение поочередно проверяет стратегии ByeDPI против популярных сервисов, измеряя пинг и стабильность.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "Проверяемые сервисы:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PresetStrategies.defaultTestTargets.forEach { target ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = DarkSurfaceVariant
                            ) {
                                Text(
                                    text = target.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (isTesting) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "⏳ Идет подбор в фоне. Экран можно заблокировать — тест не прервется.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AccentOrange
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = { tester.stopTest() },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Остановить тестирование")
                        }
                    } else {
                        Button(
                            onClick = { tester.startTest() },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentOrange)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Запустить автоподбор")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Результаты (${testResults.size}):",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (formattedDate.isNotEmpty() && !isTesting) {
                    Text(
                        text = "Проверено: $formattedDate",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (testResults.isEmpty() && !isTesting) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Тестирование еще не проводилось",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Нажмите «Запустить автоподбор», чтобы протестировать пресеты для вашего провайдера.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            } else {
                val bestYt = remember(testResults) {
                    testResults.firstOrNull { it.isWorking && (it.workingServices.contains("YT") || it.workingServices.contains("YTV")) }
                }
                val bestIg = remember(testResults) {
                    testResults.firstOrNull { it.isWorking && it.workingServices.contains("IG") }
                }

                if (bestYt != null || bestIg != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "🎯 Рекомендуемая связка профилей",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = AccentOrange
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            if (bestYt != null) {
                                Text(
                                    text = "• YouTube/Discord: ${bestYt.strategy} (${bestYt.latencyMs} мс)",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (bestIg != null) {
                                Text(
                                    text = "• Instagram: ${bestIg.strategy} (${bestIg.latencyMs} мс)",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    val newCfg = antiDpiConfig.copy(
                                        strategy = bestYt?.strategy ?: antiDpiConfig.strategy,
                                        instagramStrategy = bestIg?.strategy ?: antiDpiConfig.instagramStrategy,
                                        separateProfiles = true
                                    )
                                    repository.updateAntiDpiConfig(newCfg)
                                    if (MultiBypassVpnService.vpnStatus.value == VpnStatus.CONNECTED) {
                                        coroutineScope.launch {
                                            ByeDpiController.start(
                                                strategy = newCfg.strategy,
                                                instagramStrategy = newCfg.instagramStrategy,
                                                separateProfiles = newCfg.separateProfiles
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary)
                            ) {
                                Icon(Icons.Default.DoneAll, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Применить связку профилей", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(testResults) { res ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = DarkSurface)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = res.strategy,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    if (res.isWorking) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = GreenPrimary, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "${res.latencyMs} мс (${res.successfulSitesCount}/${res.testedSitesCount})",
                                                color = GreenPrimary,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        if (res.workingServices.isNotEmpty()) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                res.workingServices.forEach { sName ->
                                                    val shortName = formatServiceName(sName)
                                                    val sLat = res.serviceLatencies[sName]?.let { " ${it}мс" } ?: " ✓"
                                                    Surface(
                                                        shape = RoundedCornerShape(4.dp),
                                                        color = GreenPrimary.copy(alpha = 0.15f)
                                                    ) {
                                                        Text(
                                                            text = "$shortName$sLat",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = GreenPrimary,
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Cancel, contentDescription = null, tint = RedDanger, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Не работает ни один сервис", color = RedDanger, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }

                                if (res.isWorking) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    val isCurrentYt = antiDpiConfig.strategy == res.strategy
                                    val isCurrentIg = antiDpiConfig.instagramStrategy == res.strategy
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                        horizontalAlignment = Alignment.End
                                    ) {
                                        Surface(
                                            onClick = {
                                                val newCfg = antiDpiConfig.copy(strategy = res.strategy)
                                                repository.updateAntiDpiConfig(newCfg)
                                                if (MultiBypassVpnService.vpnStatus.value == VpnStatus.CONNECTED) {
                                                    coroutineScope.launch {
                                                        ByeDpiController.start(
                                                            strategy = newCfg.strategy,
                                                            instagramStrategy = newCfg.instagramStrategy,
                                                            separateProfiles = newCfg.separateProfiles
                                                        )
                                                    }
                                                }
                                            },
                                            shape = RoundedCornerShape(6.dp),
                                            color = if (isCurrentYt) GreenPrimary else DarkSurfaceVariant,
                                            modifier = Modifier.height(26.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                if (isCurrentYt) {
                                                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                                                    Spacer(modifier = Modifier.width(2.dp))
                                                }
                                                Text("YT/General", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, color = if (isCurrentYt) Color.White else TextSecondary)
                                            }
                                        }

                                        Surface(
                                            onClick = {
                                                val newCfg = antiDpiConfig.copy(instagramStrategy = res.strategy)
                                                repository.updateAntiDpiConfig(newCfg)
                                                if (MultiBypassVpnService.vpnStatus.value == VpnStatus.CONNECTED) {
                                                    coroutineScope.launch {
                                                        ByeDpiController.start(
                                                            strategy = newCfg.strategy,
                                                            instagramStrategy = newCfg.instagramStrategy,
                                                            separateProfiles = newCfg.separateProfiles
                                                        )
                                                    }
                                                }
                                            },
                                            shape = RoundedCornerShape(6.dp),
                                            color = if (isCurrentIg) GreenPrimary else DarkSurfaceVariant,
                                            modifier = Modifier.height(26.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                if (isCurrentIg) {
                                                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                                                    Spacer(modifier = Modifier.width(2.dp))
                                                }
                                                Text("Instagram", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, color = if (isCurrentIg) Color.White else TextSecondary)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private fun formatServiceName(name: String): String = when (name.trim().lowercase()) {
    "youtube", "yt" -> "YT"
    "youtube video", "ytv" -> "YTV"
    "instagram", "ig" -> "IG"
    "facebook", "fb" -> "FB"
    else -> name
}
