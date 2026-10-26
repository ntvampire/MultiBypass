package com.multibypass.app.ui.screens

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multibypass.app.core.smarthosts.SmartHostsManager
import com.multibypass.app.core.tgproxy.TelegramProxyService
import com.multibypass.app.core.vpn.MultiBypassVpnService
import com.multibypass.app.data.model.VpnStatus
import com.multibypass.app.data.repository.SettingsRepository
import com.multibypass.app.ui.components.AppPickerBottomSheet
import com.multibypass.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onNavigateToAntiDpi: () -> Unit,
    onNavigateToAutoStrategy: () -> Unit = {},
    onNavigateToSettings: () -> Unit,
    onRequestVpnPermission: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { SettingsRepository.getInstance(context) }

    val vpnStatus by MultiBypassVpnService.vpnStatus.collectAsState()
    val isTgProxyRunning by TelegramProxyService.isRunning.collectAsState()

    val smartHostsConfig by repository.smartHostsConfig.collectAsState()
    val smartHostsCount by SmartHostsManager.domainCount.collectAsState()
    val antiDpiConfig by repository.antiDpiConfig.collectAsState()
    val tgConfig by repository.tgConfig.collectAsState()
    val routedApps by repository.routedApps.collectAsState()
    val routeAllApps by repository.routeAllApps.collectAsState()

    var showAppPicker by remember { mutableStateOf(false) }
    var showDiagnosticsDialog by remember { mutableStateOf(false) }

    val isConnected = vpnStatus == VpnStatus.CONNECTED
    val isConnecting = vpnStatus == VpnStatus.CONNECTING

    val buttonColor by animateColorAsState(
        targetValue = when {
            isConnected -> GreenPrimary
            isConnecting -> AccentOrange
            else -> CardBorder
        },
        label = "btn_color"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.VpnLock,
                            contentDescription = null,
                            tint = GreenPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "MultiBypass",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showDiagnosticsDialog = true }) {
                        Icon(
                            Icons.Default.HealthAndSafety,
                            contentDescription = "Сетевой доктор",
                            tint = GreenPrimary
                        )
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Настройки",
                            tint = TextSecondary
                        )
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
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Main Connect Button
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(180.dp)
                    .clip(CircleShape)
                    .background(buttonColor.copy(alpha = 0.12f))
                    .border(3.dp, buttonColor, CircleShape)
                    .clickable {
                        if (isConnected) {
                            MultiBypassVpnService.stop(context)
                        } else {
                            onRequestVpnPermission()
                        }
                    }
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = "Подключить",
                        tint = buttonColor,
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = when {
                            isConnected -> "ПОДКЛЮЧЕНО"
                            isConnecting -> "ПОДКЛЮЧЕНИЕ..."
                            else -> "ПОДКЛЮЧИТЬ"
                        },
                        color = buttonColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Status message
            Text(
                text = when {
                    isConnected -> "Защита активна: DPI, Telegram и зарубежные сервисы"
                    isConnecting -> "Установка туннеля..."
                    else -> "Нажмите кнопку для активации обхода"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (isConnected) GreenPrimary else TextSecondary
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Routing Mode Card (Split-tunnel)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(CardBorder))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Apps, contentDescription = null, tint = AccentBlue)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Маршрутизация приложений",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            onClick = {
                                repository.setRouteAllApps(false)
                                if (isConnected) MultiBypassVpnService.reconnect(context)
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = if (!routeAllApps) AccentBlue.copy(alpha = 0.18f) else DarkBackground,
                            border = BorderStroke(1.dp, if (!routeAllApps) AccentBlue else CardBorder),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Только выбранные",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (!routeAllApps) FontWeight.Bold else FontWeight.Normal,
                                    color = if (!routeAllApps) AccentBlue else TextSecondary
                                )
                            }
                        }

                        Surface(
                            onClick = {
                                repository.setRouteAllApps(true)
                                if (isConnected) MultiBypassVpnService.reconnect(context)
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = if (routeAllApps) AccentBlue.copy(alpha = 0.18f) else DarkBackground,
                            border = BorderStroke(1.dp, if (routeAllApps) AccentBlue else CardBorder),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Все приложения",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (routeAllApps) FontWeight.Bold else FontWeight.Normal,
                                    color = if (routeAllApps) AccentBlue else TextSecondary
                                )
                            }
                        }
                    }

                    if (!routeAllApps) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Выбрано: ${routedApps.size} приложений",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                            TextButton(
                                onClick = { showAppPicker = true },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("Изменить список", color = AccentBlue, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Pillar 1: SmartHosts Geoblock Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(CardBorder))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Public, contentDescription = null, tint = AccentBlue)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Обход геоблокировок",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Switch(
                            checked = smartHostsConfig.enabled,
                            onCheckedChange = { isChecked ->
                                repository.updateSmartHostsConfig(smartHostsConfig.copy(enabled = isChecked))
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    if (smartHostsConfig.enabled) {
                        Text(
                            text = "ChatGPT, Claude, Gemini, Adobe, Canva и др.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AccentBlue
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        val totalCount = if (smartHostsCount > 0) smartHostsCount else smartHostsConfig.domainCount
                        Text(
                            text = "База Noktomezo: ${if (totalCount > 0) "$totalCount адресов" else "подключена"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    } else {
                        Text(
                            text = "Отключен (зарубежные сервисы открываются напрямую)",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Pillar 2: Anti-DPI Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToAntiDpi() },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(CardBorder))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Shield, contentDescription = null, tint = AccentOrange)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Десинхронизация TCP (Анти-DPI)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextSecondary)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Стратегия: ${antiDpiConfig.strategy}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AccentOrange,
                        maxLines = 1
                    )

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "YouTube, Discord, Rutracker (${antiDpiConfig.domains.size} адресов)",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Pillar 3: Telegram sTGWS Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(CardBorder))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Send, contentDescription = null, tint = GreenPrimary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Обход Telegram (sTGWS)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Switch(
                            checked = tgConfig.enabled,
                            onCheckedChange = { isChecked ->
                                repository.updateTgConfig(tgConfig.copy(enabled = isChecked))
                                if (isChecked) {
                                    if (isConnected) {
                                        TelegramProxyService.start(context)
                                    }
                                } else {
                                    TelegramProxyService.stop(context)
                                }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    if (tgConfig.enabled) {
                        Text(
                            text = if (isConnected && isTgProxyRunning) "● Прозрачный туннель Cloudflare активен"
                            else if (isTgProxyRunning) "○ Ожидает запуск VPN"
                            else "○ Служба остановлена",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isConnected && isTgProxyRunning) GreenPrimary else TextSecondary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Настройка прокси внутри Telegram не требуется",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    } else {
                        Text(
                            text = "Отключен (трафик Telegram не перехватывается)",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showAppPicker) {
        AppPickerBottomSheet(
            title = "Приложения для маршрутизации",
            selectedPackages = routedApps,
            onDismiss = { showAppPicker = false },
            onSave = { updated ->
                repository.updateRoutedApps(updated)
            }
        )
    }

    if (showDiagnosticsDialog) {
        com.multibypass.app.ui.components.DiagnosticsDialog(
            onDismissRequest = { showDiagnosticsDialog = false }
        )
    }
}
