package com.multibypass.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multibypass.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

enum class ServiceStatus {
    IDLE,
    CHECKING,
    ONLINE,
    BLOCKED
}

data class ServiceCheckItem(
    val id: String,
    val name: String,
    val url: String,
    var status: ServiceStatus = ServiceStatus.IDLE,
    var latencyMs: Long = 0L
)

@Composable
fun ServiceHealthWidget(
    isVpnConnected: Boolean,
    isTgProxyRunning: Boolean,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var isCheckingAll by remember { mutableStateOf(false) }

    val services = remember {
        mutableStateListOf(
            ServiceCheckItem("youtube", "YouTube", "https://www.youtube.com/generate_204"),
            ServiceCheckItem("instagram", "Instagram", "https://www.instagram.com"),
            ServiceCheckItem("discord", "Discord", "https://discord.com"),
            ServiceCheckItem("chatgpt", "ChatGPT", "https://chatgpt.com")
        )
    }

    val httpClient = remember {
        OkHttpClient.Builder()
            .connectTimeout(3500, TimeUnit.MILLISECONDS)
            .readTimeout(3500, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(false)
            .build()
    }

    fun runHealthCheck() {
        if (isCheckingAll) return
        isCheckingAll = true

        // Reset states
        for (i in services.indices) {
            services[i] = services[i].copy(status = ServiceStatus.CHECKING)
        }

        scope.launch {
            val jobs = services.indices.map { idx ->
                async(Dispatchers.IO) {
                    val item = services[idx]
                    val start = System.currentTimeMillis()
                    val req = Request.Builder()
                        .url(item.url)
                        .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0")
                        .head()
                        .build()

                    val (newStatus, latency) = try {
                        httpClient.newCall(req).execute().use { resp ->
                            val elapsed = System.currentTimeMillis() - start
                            if (resp.isSuccessful || resp.code in 200..499) {
                                Pair(ServiceStatus.ONLINE, elapsed)
                            } else {
                                Pair(ServiceStatus.BLOCKED, 0L)
                            }
                        }
                    } catch (_: Exception) {
                        Pair(ServiceStatus.BLOCKED, 0L)
                    }

                    withContext(Dispatchers.Main) {
                        services[idx] = item.copy(status = newStatus, latencyMs = latency)
                    }
                }
            }
            jobs.awaitAll()
            isCheckingAll = false
        }
    }

    // Auto check when VPN connects
    LaunchedEffect(isVpnConnected) {
        if (isVpnConnected) {
            runHealthCheck()
        } else {
            for (i in services.indices) {
                services[i] = services[i].copy(status = ServiceStatus.IDLE, latencyMs = 0L)
            }
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(CardBorder))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.NetworkCheck,
                        contentDescription = null,
                        tint = AccentBlue,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Доступность сервисов",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                IconButton(
                    onClick = { runHealthCheck() },
                    enabled = !isCheckingAll,
                    modifier = Modifier.size(32.dp)
                ) {
                    if (isCheckingAll) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp),
                            color = AccentBlue
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Проверить",
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Grid of 4 services
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                services.forEach { service ->
                    ServiceStatusBadge(
                        service = service,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
fun ServiceStatusBadge(
    service: ServiceCheckItem,
    modifier: Modifier = Modifier
) {
    val dotColor by animateColorAsState(
        targetValue = when (service.status) {
            ServiceStatus.ONLINE -> GreenPrimary
            ServiceStatus.BLOCKED -> RedDanger
            ServiceStatus.CHECKING -> AccentOrange
            ServiceStatus.IDLE -> CardBorder
        },
        label = "dot_color"
    )

    val statusText = when (service.status) {
        ServiceStatus.ONLINE -> "${service.latencyMs}мс"
        ServiceStatus.BLOCKED -> "Блок"
        ServiceStatus.CHECKING -> "..."
        ServiceStatus.IDLE -> "—"
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = DarkSurfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = service.name,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = when (service.status) {
                    ServiceStatus.ONLINE -> GreenPrimary
                    ServiceStatus.BLOCKED -> RedDanger
                    ServiceStatus.CHECKING -> AccentOrange
                    ServiceStatus.IDLE -> TextSecondary
                },
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
