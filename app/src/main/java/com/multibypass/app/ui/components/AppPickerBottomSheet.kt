package com.multibypass.app.ui.components

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.multibypass.app.data.model.ANTIDPI_PACKAGES
import com.multibypass.app.data.model.GEOBLOCK_PACKAGES
import com.multibypass.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledAppItem(
    val name: String,
    val packageName: String,
    val isSystem: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerBottomSheet(
    title: String,
    selectedPackages: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit
) {
    val context = LocalContext.current
    val pm = context.packageManager

    var searchQuery by remember { mutableStateOf("") }
    val currentSelected = remember { mutableStateListOf<String>().apply { addAll(selectedPackages) } }
    var appList by remember { mutableStateOf<List<InstalledAppItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val selfPackage = context.packageName
            val preselectedSet = selectedPackages.toSet()

            val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { appInfo ->
                    if (appInfo.packageName == selfPackage) return@filter false
                    preselectedSet.contains(appInfo.packageName) ||
                            pm.getLaunchIntentForPackage(appInfo.packageName) != null
                }
                .map { appInfo ->
                    InstalledAppItem(
                        name = appInfo.loadLabel(pm).toString(),
                        packageName = appInfo.packageName,
                        isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    )
                }
                .sortedWith(
                    compareByDescending<InstalledAppItem> { preselectedSet.contains(it.packageName) }
                        .thenBy { it.name.lowercase() }
                )
            appList = installed
            isLoading = false
        }
    }

    val geoblockApps = remember(appList) {
        appList.filter { GEOBLOCK_PACKAGES.contains(it.packageName) }
    }

    val antiDpiApps = remember(appList) {
        appList.filter { ANTIDPI_PACKAGES.contains(it.packageName) }
    }

    val otherApps = remember(appList) {
        appList.filter { !GEOBLOCK_PACKAGES.contains(it.packageName) && !ANTIDPI_PACKAGES.contains(it.packageName) }
    }

    val filteredApps = remember(appList, searchQuery) {
        if (searchQuery.isBlank()) appList
        else appList.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                    it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Закрыть")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Поиск приложения...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Quick Selection Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val allSelected = geoblockApps.isNotEmpty() && geoblockApps.all { currentSelected.contains(it.packageName) }
                        if (allSelected) {
                            geoblockApps.forEach { currentSelected.remove(it.packageName) }
                        } else {
                            geoblockApps.forEach { if (!currentSelected.contains(it.packageName)) currentSelected.add(it.packageName) }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("+ ИИ / Геоблок", fontSize = 12.sp, maxLines = 1)
                }

                OutlinedButton(
                    onClick = {
                        val allSelected = antiDpiApps.isNotEmpty() && antiDpiApps.all { currentSelected.contains(it.packageName) }
                        if (allSelected) {
                            antiDpiApps.forEach { currentSelected.remove(it.packageName) }
                        } else {
                            antiDpiApps.forEach { if (!currentSelected.contains(it.packageName)) currentSelected.add(it.packageName) }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("+ Медиа / DPI", fontSize = 12.sp, maxLines = 1)
                }

                if (currentSelected.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { currentSelected.clear() },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Снять все", fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (searchQuery.isNotBlank()) {
                        items(filteredApps, key = { it.packageName }) { app ->
                            AppPickerRow(
                                app = app,
                                isChecked = currentSelected.contains(app.packageName),
                                onToggle = {
                                    if (currentSelected.contains(app.packageName)) currentSelected.remove(app.packageName)
                                    else currentSelected.add(app.packageName)
                                },
                                pm = pm
                            )
                        }
                    } else {
                        // Section 1: Geoblock / AI
                        if (geoblockApps.isNotEmpty()) {
                            item(key = "header_geo") {
                                SectionHeader(
                                    title = "🌐 Обход геоблокировок (SmartDNS)",
                                    subtitle = "ChatGPT, Gemini, Claude, Spotify, Canva...",
                                    color = AccentBlue,
                                    allChecked = geoblockApps.all { currentSelected.contains(it.packageName) },
                                    onToggleAll = {
                                        val allSel = geoblockApps.all { currentSelected.contains(it.packageName) }
                                        if (allSel) {
                                            geoblockApps.forEach { currentSelected.remove(it.packageName) }
                                        } else {
                                            geoblockApps.forEach { if (!currentSelected.contains(it.packageName)) currentSelected.add(it.packageName) }
                                        }
                                    }
                                )
                            }
                            items(geoblockApps, key = { it.packageName }) { app ->
                                AppPickerRow(
                                    app = app,
                                    isChecked = currentSelected.contains(app.packageName),
                                    onToggle = {
                                        if (currentSelected.contains(app.packageName)) currentSelected.remove(app.packageName)
                                        else currentSelected.add(app.packageName)
                                    },
                                    pm = pm
                                )
                            }
                        }

                        // Section 2: Anti-DPI
                        if (antiDpiApps.isNotEmpty()) {
                            item(key = "header_antidpi") {
                                SectionHeader(
                                    title = "🛡️ Обход замедлений и ТСПУ (Anti-DPI)",
                                    subtitle = "YouTube, Instagram, Discord, X/Twitter, Telegram...",
                                    color = GreenPrimary,
                                    allChecked = antiDpiApps.all { currentSelected.contains(it.packageName) },
                                    onToggleAll = {
                                        val allSel = antiDpiApps.all { currentSelected.contains(it.packageName) }
                                        if (allSel) {
                                            antiDpiApps.forEach { currentSelected.remove(it.packageName) }
                                        } else {
                                            antiDpiApps.forEach { if (!currentSelected.contains(it.packageName)) currentSelected.add(it.packageName) }
                                        }
                                    }
                                )
                            }
                            items(antiDpiApps, key = { it.packageName }) { app ->
                                AppPickerRow(
                                    app = app,
                                    isChecked = currentSelected.contains(app.packageName),
                                    onToggle = {
                                        if (currentSelected.contains(app.packageName)) currentSelected.remove(app.packageName)
                                        else currentSelected.add(app.packageName)
                                    },
                                    pm = pm
                                )
                            }
                        }

                        // Section 3: Other installed apps
                        if (otherApps.isNotEmpty()) {
                            item(key = "header_other") {
                                SectionHeader(
                                    title = "📱 Другие установленные приложения",
                                    subtitle = "Остальные приложения на устройстве (${otherApps.size})",
                                    color = TextSecondary,
                                    allChecked = otherApps.all { currentSelected.contains(it.packageName) },
                                    onToggleAll = {
                                        val allSel = otherApps.all { currentSelected.contains(it.packageName) }
                                        if (allSel) {
                                            otherApps.forEach { currentSelected.remove(it.packageName) }
                                        } else {
                                            otherApps.forEach { if (!currentSelected.contains(it.packageName)) currentSelected.add(it.packageName) }
                                        }
                                    }
                                )
                            }
                            items(otherApps, key = { it.packageName }) { app ->
                                AppPickerRow(
                                    app = app,
                                    isChecked = currentSelected.contains(app.packageName),
                                    onToggle = {
                                        if (currentSelected.contains(app.packageName)) currentSelected.remove(app.packageName)
                                        else currentSelected.add(app.packageName)
                                    },
                                    pm = pm
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        onSave(currentSelected.toList())
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Сохранить (${currentSelected.size} выбрано)")
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    subtitle: String,
    color: Color,
    allChecked: Boolean,
    onToggleAll: () -> Unit
) {
    Surface(
        color = DarkSurfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
            TextButton(
                onClick = onToggleAll,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Text(
                    text = if (allChecked) "Снять" else "Выбрать все",
                    fontSize = 11.sp,
                    color = color
                )
            }
        }
    }
}

@Composable
private fun AppPickerRow(
    app: InstalledAppItem,
    isChecked: Boolean,
    onToggle: () -> Unit,
    pm: PackageManager
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val iconBitmap = remember(app.packageName) {
            try {
                pm.getApplicationIcon(app.packageName).toBitmap(64, 64).asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }

        if (iconBitmap != null) {
            Image(
                bitmap = iconBitmap,
                contentDescription = null,
                modifier = Modifier.size(38.dp)
            )
        } else {
            Box(modifier = Modifier.size(38.dp))
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Checkbox(
            checked = isChecked,
            onCheckedChange = { onToggle() }
        )
    }
}
