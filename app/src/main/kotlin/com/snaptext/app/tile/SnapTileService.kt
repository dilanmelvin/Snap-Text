package com.snaptext.app.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.snaptext.app.R
import com.snaptext.app.accessibility.SnapAccessibilityService

@RequiresApi(Build.VERSION_CODES.N)
class SnapTileService : TileService() {
    override fun onTileAdded() {
        super.onTileAdded()
        setInactive()
    }

    override fun onStartListening() {
        super.onStartListening()
        setInactive()
    }

    override fun onClick() {
        super.onClick()
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            updateTile()
        }

        if (SnapAccessibilityService.scanVisibleText()) {
            setInactive()
            return
        }

        Toast.makeText(this, R.string.accessibility_required, Toast.LENGTH_LONG).show()
        val settingsIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivityAndCollapseCompat(settingsIntent)
        setInactive()
    }

    private fun startActivityAndCollapseCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun setInactive() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }
}
