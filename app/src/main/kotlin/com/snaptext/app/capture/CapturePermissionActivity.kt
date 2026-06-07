package com.snaptext.app.capture

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.snaptext.app.MainActivity
import com.snaptext.app.R

class CapturePermissionActivity : AppCompatActivity() {
    private var hasStartedRequest = false

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                CaptureResultReceiver.projectionResultCode = result.resultCode
                CaptureResultReceiver.projectionData = result.data
                startCaptureService()
            } else {
                Toast.makeText(this, R.string.screen_capture_denied, Toast.LENGTH_SHORT).show()
            }
            finish()
            overridePendingTransition(0, 0)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.overlay_permission_denied, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        if (savedInstanceState == null) {
            requestScreenCapturePermissionOnce()
        }
    }

    private fun requestScreenCapturePermissionOnce() {
        if (hasStartedRequest) return
        hasStartedRequest = true

        val projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            projectionManager.createScreenCaptureIntent(
                MediaProjectionConfig.createConfigForDefaultDisplay()
            )
        } else {
            projectionManager.createScreenCaptureIntent()
        }
        screenCaptureLauncher.launch(captureIntent)
    }

    private fun startCaptureService() {
        val serviceIntent = ScreenCaptureService.buildStartIntent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
}
