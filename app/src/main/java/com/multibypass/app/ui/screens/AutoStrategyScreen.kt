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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.multibypass.app.core.byedpi.StrategyTester
import com.multibypass.app.data.repository.SettingsRepository
import com.multibypass.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoStrategyScreen(
    onNavigateBack: () -> Unit
) {
    BackHandler(onBack = onNavigateBack)

    val context = LocalContext.current
    val repository = remember { SettingsRepository.getInstance(context) }
    val antiDpiConfig by repository.antiDpiConfig.collectAsState()

    val tester = remember { StrategyTester(context) }
    val isTesting by tester.isTesting.collectAsState()
    val testResults by tester.testResults.collectAsState()

    DisposableEffect(Unit) {
        onDispose {
            tester.stopTest()
        }
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
                        text = "Тестирование обхода блокировок",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Приложение поочередно проверяет стратегии ByeDPI против YouTube и Discord, измеряя пинг и стабильность.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    if (isTesting) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(12.dp))
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

            Text(
                text = "Результаты (${testResults.size}):",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(8.dp))

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
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (res.isWorking) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = GreenPrimary, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("${res.latencyMs} мс (${res.successfulSitesCount}/${res.testedSitesCount} сайтов)", color = GreenPrimary, style = MaterialTheme.typography.bodySmall)
                                    } else {
                                        Icon(Icons.Default.Cancel, contentDescription = null, tint = RedDanger, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Не работает", color = RedDanger, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }

                            if (res.isWorking) {
                                val isCurrent = antiDpiConfig.strategy == res.strategy
                                if (isCurrent) {
                                    Text("Активна", color = GreenPrimary, fontWeight = FontWeight.Bold)
                                } else {
                                    FilledTonalButton(
                                        onClick = {
                                            repository.updateAntiDpiConfig(antiDpiConfig.copy(strategy = res.strategy))
                                        },
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("Применить")
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
