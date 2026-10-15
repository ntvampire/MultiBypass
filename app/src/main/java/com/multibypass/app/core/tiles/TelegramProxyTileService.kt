package com.multibypass.app.core.tiles

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.multibypass.app.core.tgproxy.TelegramProxyService
import com.multibypass.app.data.repository.SettingsRepository
import kotlinx.coroutines.*

@RequiresApi(Build.VERSION_CODES.N)
class TelegramProxyTileService : TileService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var statusJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        updateTileState(TelegramProxyService.isRunning.value)
        statusJob?.cancel()
        statusJob = serviceScope.launch {
            TelegramProxyService.isRunning.collect { running ->
                updateTileState(running)
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
        val isRunning = TelegramProxyService.isRunning.value
        val repository = SettingsRepository.getInstance(applicationContext)
        if (isRunning) {
            TelegramProxyService.stop(this)
            repository.updateTgConfig(repository.tgConfig.value.copy(enabled = false))
            updateTileState(false)
        } else {
            repository.updateTgConfig(repository.tgConfig.value.copy(enabled = true))
            TelegramProxyService.start(this)
            updateTileState(true)
        }
    }

    private fun updateTileState(running: Boolean) {
        val tile = qsTile ?: return
        if (running) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "TG Proxy"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "127.0.0.1:1443"
            }
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "TG Proxy"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "Остановлен"
            }
        }
        tile.updateTile()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
