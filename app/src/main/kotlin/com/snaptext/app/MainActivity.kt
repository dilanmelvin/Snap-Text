package com.snaptext.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.snaptext.app.databinding.ActivityMainBinding
import com.snaptext.app.utils.PermissionHelper

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            updateOverlayStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupButtons()
        updateOverlayStatus()
    }

    override fun onResume() {
        super.onResume()
        updateOverlayStatus()
    }

    private fun setupButtons() {
        binding.btnOverlayPermission.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }

        binding.btnOpenQuickSettings.setOnClickListener {
            Toast.makeText(this, R.string.accessibility_required, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun updateOverlayStatus() {
        val hasPermission = PermissionHelper.canDrawOverlay(this)
        binding.tvOverlayStatus.text = if (hasPermission) {
            "Granted"
        } else {
            "Not granted"
        }
        binding.tvOverlayStatus.setTextColor(
            if (hasPermission) {
                ContextCompat.getColor(this, R.color.accent)
            } else {
                ContextCompat.getColor(this, R.color.danger)
            }
        )
        binding.tvAllSet.visibility = if (hasPermission) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
    }
}
