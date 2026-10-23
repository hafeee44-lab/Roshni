package io.github.hafeee44lab.solargrid.service

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.hafeee44lab.solargrid.MainActivity
import io.github.hafeee44lab.solargrid.R
import io.github.hafeee44lab.solargrid.data.AppGraph
import io.github.hafeee44lab.solargrid.data.phrases
import io.github.hafeee44lab.solargrid.domain.StateRole
import io.github.hafeee44lab.solargrid.domain.buildHomeUi

/** Quick Settings tile: grid state and battery at a glance from the shade. Tap to open. */
class StatusTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 30, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    private fun render() {
        val tile = qsTile ?: return
        val graph = AppGraph.get(this)
        val settings = graph.settings.load()
        val ui = buildHomeUi(graph.state(settings.profile).snapshot, settings.monitoring, p = phrases())

        tile.label = when (ui.heroRole) {
            StateRole.UP -> getString(R.string.mains_on)
            StateRole.DOWN -> getString(R.string.mains_off)
            StateRole.NEUTRAL -> ui.heroState
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (ui.soc != null) getString(R.string.tile_battery, ui.socText) else getString(R.string.no_data)
        }
        tile.icon = Icon.createWithResource(this, if (ui.heroRole == StateRole.DOWN) R.drawable.ic_stat_home_off else R.drawable.ic_stat_home)
        tile.state = when {
            !settings.monitoring -> Tile.STATE_INACTIVE
            ui.heroRole == StateRole.DOWN -> Tile.STATE_ACTIVE
            else -> Tile.STATE_ACTIVE
        }
        tile.updateTile()
    }

    companion object {
        fun refresh(context: Context) {
            try {
                requestListeningState(context, ComponentName(context, StatusTile::class.java))
            } catch (e: Exception) {
                // The tile is not added; nothing to refresh.
            }
        }
    }
}
