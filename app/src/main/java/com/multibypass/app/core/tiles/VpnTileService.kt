package com.multibypass.app.core.tiles

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.multibypass.app.MainActivity
import com.multibypass.app.core.vpn.MultiBypassVpnService
import com.multibypass.app.data.model.VpnStatus
import kotlinx.coroutines.*

@RequiresApi(Build.VERSION_CODES.N)
class VpnTileService : TileService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var statusJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        updateTileState(MultiBypassVpnService.vpnStatus.value)
        statusJob?.cancel()
        statusJob = serviceScope.launch {
            MultiBypassVpnService.vpnStatus.collect { status ->
                updateTileState(status)
            }
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        statusJob?.cancel()
        statusJob = null
    }

    override fun onClick() {
        super.onClick()
        val currentStatus = MultiBypassVpnService.vpnStatus.value
        if (currentStatus == VpnStatus.CONNECTED || currentStatus == VpnStatus.CONNECTING) {
            MultiBypassVpnService.stop(this)
            updateTileState(VpnStatus.DISCONNECTED)
        } else {
            val prepareIntent = VpnService.prepare(this)
            if (prepareIntent == null) {
                MultiBypassVpnService.start(this)
                updateTileState(VpnStatus.CONNECTING)
            } else {
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivityAndCollapse(intent)
            }
        }
    }

    private fun updateTileState(status: VpnStatus) {
        val tile = qsTile ?: return
        when (status) {
            VpnStatus.CONNECTED -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "MultiBypass"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Активен"
                }
            }
            VpnStatus.CONNECTING -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.label = "MultiBypass"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Подключение..."
                }
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "MultiBypass"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Отключен"
                }
            }
        }
        tile.updateTile()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
