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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.multibypass.app.core.byedpi.ByeDpiController
import com.multibypass.app.core.byedpi.PresetStrategies
import com.multibypass.app.core.vpn.MultiBypassVpnService
import com.multibypass.app.data.model.AntiDpiGroupConfig
import com.multibypass.app.data.model.VpnStatus
import com.multibypass.app.data.repository.SettingsRepository
import com.multibypass.app.ui.components.AppPickerBottomSheet
import com.multibypass.app.ui.theme.AccentOrange
import com.multibypass.app.ui.theme.DarkBackground
import com.multibypass.app.ui.theme.DarkSurface
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

    BackHandler(onBack = onNavigateBack)

    var showAppPicker by remember { mutableStateOf(false) }
    var newDomainInput by remember { mutableStateOf("") }
    var showStrategyDialog by remember { mutableStateOf(false) }

    val presetStrategies = remember { PresetStrategies.loadStrategies(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Группа 2: Анти-DPI", fontWeight = FontWeight.Bold) },
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
                            Text("Список пресетов")
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
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Application Selection
            Button(
                onClick = { showAppPicker = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Icon(Icons.Default.Apps, contentDescription = null, modifier = Modifier.size(20.dp), tint = AccentOrange)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Выбрать приложения (${antiDpiConfig.appPackages.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
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

    if (showAppPicker) {
        AppPickerBottomSheet(
            title = "Приложения для Анти-DPI",
            selectedPackages = antiDpiConfig.appPackages,
            onDismiss = { showAppPicker = false },
            onSave = { updated ->
                repository.updateAntiDpiConfig(antiDpiConfig.copy(appPackages = updated))
            }
        )
    }
}
