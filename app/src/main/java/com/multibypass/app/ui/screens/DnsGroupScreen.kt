package com.multibypass.app.ui.screens

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
import com.multibypass.app.core.dns.DnsPresets
import com.multibypass.app.data.model.DnsGroupConfig
import com.multibypass.app.data.model.DnsMode
import com.multibypass.app.data.repository.SettingsRepository
import com.multibypass.app.ui.components.AppPickerBottomSheet
import com.multibypass.app.ui.theme.AccentBlue
import com.multibypass.app.ui.theme.DarkBackground
import com.multibypass.app.ui.theme.DarkSurface
import com.multibypass.app.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DnsGroupScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { SettingsRepository.getInstance(context) }
    val dnsConfig by repository.dnsConfig.collectAsState()

    var showAppPicker by remember { mutableStateOf(false) }
    var newDomainInput by remember { mutableStateOf("") }
    var expandedPresets by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Группа 1: Свой DNS", fontWeight = FontWeight.Bold) },
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

            // Protocol Mode Toggle
            Text("Протокол DNS:", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                FilterChip(
                    selected = dnsConfig.mode == DnsMode.DOH,
                    onClick = { repository.updateDnsConfig(dnsConfig.copy(mode = DnsMode.DOH)) },
                    label = { Text("DNS-over-HTTPS (DoH)") },
                    modifier = Modifier.weight(1f),
                    leadingIcon = if (dnsConfig.mode == DnsMode.DOH) {
                        { Icon(Icons.Default.Check, contentDescription = null) }
                    } else null
                )
                Spacer(modifier = Modifier.width(8.dp))
                FilterChip(
                    selected = dnsConfig.mode == DnsMode.STANDARD,
                    onClick = { repository.updateDnsConfig(dnsConfig.copy(mode = DnsMode.STANDARD)) },
                    label = { Text("Стандартный (IP)") },
                    modifier = Modifier.weight(1f),
                    leadingIcon = if (dnsConfig.mode == DnsMode.STANDARD) {
                        { Icon(Icons.Default.Check, contentDescription = null) }
                    } else null
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Preset Dropdown
            Text("Готовые серверы DNS:", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))

            ExposedDropdownMenuBox(
                expanded = expandedPresets,
                onExpandedChange = { expandedPresets = !expandedPresets }
            ) {
                val selectedPreset = DnsPresets.findById(dnsConfig.presetId)
                OutlinedTextField(
                    value = selectedPreset.name,
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedPresets) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                ExposedDropdownMenu(
                    expanded = expandedPresets,
                    onDismissRequest = { expandedPresets = false }
                ) {
                    DnsPresets.list.forEach { preset ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(preset.name, fontWeight = FontWeight.Medium)
                                    if (preset.description.isNotBlank()) {
                                        Text(preset.description, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                    }
                                }
                            },
                            onClick = {
                                repository.updateDnsConfig(dnsConfig.copy(presetId = preset.id))
                                expandedPresets = false
                            }
                        )
                    }
                }
            }

            // NextDNS profile input
            if (dnsConfig.presetId == "nextdns") {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = dnsConfig.nextDnsProfileId,
                    onValueChange = { repository.updateDnsConfig(dnsConfig.copy(nextDnsProfileId = it)) },
                    label = { Text("ID профиля NextDNS (например, abc123)") },
                    placeholder = { Text("abc123") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
            }

            // Custom DNS inputs
            if (dnsConfig.presetId == "custom") {
                Spacer(modifier = Modifier.height(12.dp))
                if (dnsConfig.mode == DnsMode.DOH) {
                    OutlinedTextField(
                        value = dnsConfig.customDohUrl,
                        onValueChange = { repository.updateDnsConfig(dnsConfig.copy(customDohUrl = it)) },
                        label = { Text("URL DoH сервера") },
                        placeholder = { Text("https://example.com/dns-query") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                } else {
                    OutlinedTextField(
                        value = dnsConfig.customStandardIp,
                        onValueChange = { repository.updateDnsConfig(dnsConfig.copy(customStandardIp = it)) },
                        label = { Text("IP адрес DNS сервера") },
                        placeholder = { Text("1.1.1.1") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Application Selection
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Выбранные приложения (${dnsConfig.appPackages.size}):",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Button(
                    onClick = { showAppPicker = true },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Apps, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Выбрать")
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
                    text = "Адреса и домены (${dnsConfig.domains.size}):",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(
                    onClick = {
                        repository.updateDnsConfig(dnsConfig.copy(domains = DnsGroupConfig().domains))
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
                    placeholder = { Text("Добавить домен (напр. spotify.com)") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        val trimmed = newDomainInput.trim().lowercase()
                        if (trimmed.isNotBlank() && !dnsConfig.domains.contains(trimmed)) {
                            repository.updateDnsConfig(dnsConfig.copy(domains = dnsConfig.domains + trimmed))
                            newDomainInput = ""
                        }
                    },
                    modifier = Modifier.size(50.dp)
                ) {
                    Icon(Icons.Default.AddCircle, contentDescription = "Добавить", tint = AccentBlue, modifier = Modifier.size(36.dp))
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            dnsConfig.domains.forEach { domain ->
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
                                repository.updateDnsConfig(dnsConfig.copy(domains = dnsConfig.domains - domain))
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

    if (showAppPicker) {
        AppPickerBottomSheet(
            title = "Приложения для Своего DNS",
            selectedPackages = dnsConfig.appPackages,
            onDismiss = { showAppPicker = false },
            onSave = { updated ->
                repository.updateDnsConfig(dnsConfig.copy(appPackages = updated))
            }
        )
    }
}
