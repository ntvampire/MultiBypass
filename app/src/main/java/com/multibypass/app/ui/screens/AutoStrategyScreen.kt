package com.multibypass.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multibypass.app.core.byedpi.ByeDpiController
import com.multibypass.app.core.byedpi.PresetStrategies
import com.multibypass.app.core.byedpi.StrategyTester
import com.multibypass.app.core.vpn.MultiBypassVpnService
import com.multibypass.app.data.model.StrategyTestResult
import com.multibypass.app.data.model.VpnStatus
import com.multibypass.app.data.repository.SettingsRepository
import com.multibypass.app.ui.theme.*
import kotlinx.coroutines.launch

enum class StrategyFilterTab(val label: String) {
    ALL("Все"),
    YOUTUBE("▶ YouTube / Discord"),
    INSTAGRAM("📷 Instagram")
}

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

    var selectedFilter by remember { mutableStateOf(StrategyFilterTab.ALL) }

    val formattedDate = remember(lastTimestamp) {
        if (lastTimestamp > 0) {
            val sdf = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
            sdf.format(java.util.Date(lastTimestamp))
        } else ""
    }

    // Независимый расчет лучшей стратегии для YouTube/Discord (YT + YTV)
    val bestYt = remember(testResults) {
        testResults
            .filter { it.isWorking && it.hasYt() }
            .minWithOrNull(
                compareByDescending<StrategyTestResult> { it.hasFullYt() }
                    .thenBy { it.getYtLatency() ?: Long.MAX_VALUE }
            )
    }

    // Независимый расчет лучшей стратегии для Instagram (IG)
    val bestIg = remember(testResults) {
        testResults
            .filter { it.isWorking && it.hasIg() }
            .minByOrNull { it.getIgLatency() ?: Long.MAX_VALUE }
    }

    // Динамическая сортировка списка под выбранную вкладку
    val sortedResults = remember(testResults, selectedFilter) {
        when (selectedFilter) {
            StrategyFilterTab.ALL -> testResults
            StrategyFilterTab.YOUTUBE -> testResults.sortedWith(
                compareByDescending<StrategyTestResult> { it.hasYt() }
                    .thenByDescending { it.hasFullYt() }
                    .thenBy { it.getYtLatency() ?: Long.MAX_VALUE }
                    .thenBy { it.latencyMs }
            )
            StrategyFilterTab.INSTAGRAM -> testResults.sortedWith(
                compareByDescending<StrategyTestResult> { it.hasIg() }
                    .thenBy { it.getIgLatency() ?: Long.MAX_VALUE }
                    .thenBy { it.latencyMs }
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Автоподбор стратегий", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
            )
        },
        containerColor = DarkBackground
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
                // Компактный блок запуска/остановки теста
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Тестирование стратегий",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf("YT", "YTV", "IG").forEach { name ->
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = DarkSurfaceVariant
                                    ) {
                                        Text(
                                            text = name,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextSecondary,
                                            fontSize = 10.sp,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }

                        if (isTesting) {
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp),
                                color = AccentOrange,
                                trackColor = DarkSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "⏳ В фоне (экран можно гасить)",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = 11.sp,
                                    color = AccentOrange
                                )
                                OutlinedButton(
                                    onClick = { tester.stopTest() },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text("Остановить", fontSize = 12.sp)
                                }
                            }
                        } else {
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = { tester.startTest() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(38.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AccentOrange),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Запустить автоподбор", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }

            // Компактный блок рекомендуемой связки
            if (bestYt != null || bestIg != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "🎯 Рекомендуемая связка профилей",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = AccentOrange
                                )
                                if (formattedDate.isNotEmpty() && !isTesting) {
                                    Text(
                                        text = formattedDate,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.sp,
                                        color = TextSecondary
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            if (bestYt != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                        Text("• YT/General: ", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            text = bestYt.strategy,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "${bestYt.getYtLatency() ?: bestYt.latencyMs} мс",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = GreenPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            if (bestIg != null) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                        Text("• Instagram: ", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            text = bestIg.strategy,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "${bestIg.getIgLatency() ?: bestIg.latencyMs} мс",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = GreenPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
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
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(36.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Icon(Icons.Default.DoneAll, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Применить связку профилей", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            // Переключатель пулов / фильтр сортировки
            if (testResults.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(
                            StrategyFilterTab.ALL to "Все (${testResults.size})",
                            StrategyFilterTab.YOUTUBE to "▶ YouTube",
                            StrategyFilterTab.INSTAGRAM to "📷 Instagram"
                        ).forEach { (tab, label) ->
                            val isSelected = selectedFilter == tab
                            Surface(
                                onClick = { selectedFilter = tab },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) AccentOrange.copy(alpha = 0.2f) else DarkSurface,
                                border = if (isSelected) BorderStroke(1.dp, AccentOrange) else null,
                                modifier = Modifier.height(32.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) AccentOrange else TextSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Пустое состояние
            if (testResults.isEmpty() && !isTesting) {
                item {
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
                }
            } else {
                items(sortedResults, key = { it.strategy }) { res ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkSurface)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = res.strategy,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp
                                )
                                Spacer(modifier = Modifier.height(3.dp))

                                when (selectedFilter) {
                                    StrategyFilterTab.YOUTUBE -> {
                                        if (res.hasYt()) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = GreenPrimary, modifier = Modifier.size(14.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = "${res.getYtLatency()} мс",
                                                    color = GreenPrimary,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = if (res.hasFullYt()) " (YT + Видео)" else if (res.serviceLatencies.containsKey("YT")) " (только сайт)" else " (только видео)",
                                                    color = TextSecondary,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontSize = 10.sp
                                                )
                                            }
                                        } else {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.Cancel, contentDescription = null, tint = RedDanger, modifier = Modifier.size(14.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Не работает для YouTube", color = RedDanger, style = MaterialTheme.typography.bodySmall, fontSize = 11.sp)
                                            }
                                        }
                                    }
                                    StrategyFilterTab.INSTAGRAM -> {
                                        if (res.hasIg()) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = GreenPrimary, modifier = Modifier.size(14.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = "${res.getIgLatency()} мс (Instagram)",
                                                    color = GreenPrimary,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        } else {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.Cancel, contentDescription = null, tint = RedDanger, modifier = Modifier.size(14.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Не работает для Instagram", color = RedDanger, style = MaterialTheme.typography.bodySmall, fontSize = 11.sp)
                                            }
                                        }
                                    }
                                    StrategyFilterTab.ALL -> {
                                        if (res.isWorking) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = GreenPrimary, modifier = Modifier.size(14.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = "${res.latencyMs} мс (${res.successfulSitesCount}/${res.testedSitesCount})",
                                                    color = GreenPrimary,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        } else {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.Cancel, contentDescription = null, tint = RedDanger, modifier = Modifier.size(14.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Не работает ни один сервис", color = RedDanger, style = MaterialTheme.typography.bodySmall, fontSize = 11.sp)
                                            }
                                        }
                                    }
                                }

                                if (res.workingServices.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        res.workingServices.forEach { sName ->
                                            val shortName = formatServiceName(sName)
                                            val sLat = res.serviceLatencies[sName]?.let { " ${it}мс" } ?: " ✓"
                                            val isHighlight = when (selectedFilter) {
                                                StrategyFilterTab.YOUTUBE -> shortName == "YT" || shortName == "YTV"
                                                StrategyFilterTab.INSTAGRAM -> shortName == "IG"
                                                StrategyFilterTab.ALL -> true
                                            }
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = if (isHighlight) GreenPrimary.copy(alpha = 0.15f) else DarkSurfaceVariant
                                            ) {
                                                Text(
                                                    text = "$shortName$sLat",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (isHighlight) GreenPrimary else TextSecondary,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
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
                                        border = if (!isCurrentYt && selectedFilter == StrategyFilterTab.YOUTUBE && res.hasYt()) BorderStroke(1.dp, AccentOrange.copy(alpha = 0.6f)) else null,
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
                                            Text(
                                                "YT/General",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontSize = 10.sp,
                                                color = if (isCurrentYt) Color.White else if (selectedFilter == StrategyFilterTab.YOUTUBE) AccentOrange else TextSecondary
                                            )
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
                                        border = if (!isCurrentIg && selectedFilter == StrategyFilterTab.INSTAGRAM && res.hasIg()) BorderStroke(1.dp, AccentOrange.copy(alpha = 0.6f)) else null,
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
                                            Text(
                                                "Instagram",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontSize = 10.sp,
                                                color = if (isCurrentIg) Color.White else if (selectedFilter == StrategyFilterTab.INSTAGRAM) AccentOrange else TextSecondary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

private fun StrategyTestResult.getYtLatency(): Long? {
    val yt = serviceLatencies["YT"]
    val ytv = serviceLatencies["YTV"]
    return when {
        yt != null && ytv != null -> (yt + ytv) / 2
        yt != null -> yt
        ytv != null -> ytv
        else -> null
    }
}

private fun StrategyTestResult.hasYt(): Boolean =
    serviceLatencies.containsKey("YT") || serviceLatencies.containsKey("YTV")

private fun StrategyTestResult.hasFullYt(): Boolean =
    serviceLatencies.containsKey("YT") && serviceLatencies.containsKey("YTV")

private fun StrategyTestResult.getIgLatency(): Long? = serviceLatencies["IG"]

private fun StrategyTestResult.hasIg(): Boolean = serviceLatencies.containsKey("IG")

private fun formatServiceName(name: String): String = when (name.trim().lowercase()) {
    "youtube", "yt" -> "YT"
    "youtube video", "ytv" -> "YTV"
    "instagram", "ig" -> "IG"
    "facebook", "fb" -> "FB"
    else -> name
}
