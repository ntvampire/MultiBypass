package com.multibypass.app.ui.screens

import android.content.Context
import androidx.compose.animation.animateColorAsState
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
import com.multibypass.app.core.dns.DnsPresets
import com.multibypass.app.core.tgproxy.TelegramProxyController
import com.multibypass.app.core.tgproxy.TelegramProxyService
import com.multibypass.app.core.vpn.MultiBypassVpnService
import com.multibypass.app.data.model.VpnStatus
import com.multibypass.app.data.repository.SettingsRepository
import com.multibypass.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onNavigateToDns: () -> Unit,
    onNavigateToAntiDpi: () -> Unit,
    onNavigateToAutoStrategy: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onRequestVpnPermission: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { SettingsRepository.getInstance(context) }

    val vpnStatus by MultiBypassVpnService.vpnStatus.collectAsState()
    val isTgProxyRunning by TelegramProxyService.isRunning.collectAsState()

    val dnsConfig by repository.dnsConfig.collectAsState()
    val antiDpiConfig by repository.antiDpiConfig.collectAsState()
    val tgConfig by repository.tgConfig.collectAsState()

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
                            modifier = Modifier.size(28.dp)
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
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkBackground
                )
            )
        },
        containerColor = DarkBackground
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Main Power Button
            Box(
                modifier = Modifier
                    .size(160.dp)
                    .clip(CircleShape)
                    .background(buttonColor.copy(alpha = 0.15f))
                    .border(3.dp, buttonColor, CircleShape)
                    .clickable {
                        if (isConnected) {
                            MultiBypassVpnService.stop(context)
                        } else {
                            onRequestVpnPermission()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = "Включение",
                        tint = if (isConnected) GreenPrimary else TextPrimary,
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = when (vpnStatus) {
                            VpnStatus.CONNECTED -> "АКТИВЕН"
                            VpnStatus.CONNECTING -> "ПОДКЛЮЧЕНИЕ..."
                            VpnStatus.FAILED -> "СБОЙ"
                            VpnStatus.DISCONNECTED -> "ОТКЛЮЧЕН"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isConnected) GreenPrimary else TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Direct Bypass notice banner
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = DarkSurfaceVariant.copy(alpha = 0.6f)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = GreenPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Все остальные адреса и приложения работают напрямую через вашу сеть",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Group 1: Custom DNS Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToDns() },
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
                            Icon(Icons.Default.Dns, contentDescription = null, tint = AccentBlue)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Группа 1: Свой DNS",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextSecondary)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    val presetName = DnsPresets.findById(dnsConfig.presetId).name
                    Text(
                        text = "Сервер: $presetName (${dnsConfig.mode.name})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AccentBlue
                    )

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Адресов: ${dnsConfig.domains.size} | Приложений: ${dnsConfig.appPackages.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Group 2: Anti-DPI Card
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
                                text = "Группа 2: Анти-DPI (ByeByeDPI)",
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
                        text = "Адресов: ${antiDpiConfig.domains.size} | Приложений: ${antiDpiConfig.appPackages.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedButton(
                        onClick = { onNavigateToAutoStrategy() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.AutoMode, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Автоподбор лучшей стратегии")
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Telegram Proxy Card
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
                            Icon(Icons.Default.Send, contentDescription = null, tint = AccentBlue)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Telegram WS Proxy",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Switch(
                            checked = tgConfig.enabled,
                            onCheckedChange = { isChecked ->
                                repository.updateTgConfig(tgConfig.copy(enabled = isChecked))
                                if (isChecked) {
                                    TelegramProxyService.start(context)
                                } else {
                                    TelegramProxyService.stop(context)
                                }
                            }
                        )
                    }

                    Text(
                        text = if (isTgProxyRunning) "Активен на 127.0.0.1:${tgConfig.port}" else "Остановлен",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isTgProxyRunning) GreenPrimary else TextSecondary
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = { TelegramProxyController.openTelegramProxy(context, tgConfig.port) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                    ) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Подключить в Telegram")
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
