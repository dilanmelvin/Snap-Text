package com.snaptext.app.utils

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.snaptext.app.R

/**
 * A tiny, invisible activity whose only job is to write text to the clipboard.
 *
 * Overlay windows and services can be blocked from writing the clipboard on some
 * devices (MIUI, ColorOS, etc.). A foreground activity always has focus, so
 * writing from here works reliably everywhere. The activity finishes immediately
 * and shows no UI.
 */
class CopyActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = intent.getStringExtra(EXTRA_TEXT)
        if (!text.isNullOrEmpty()) {
            try {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("SnapText", text))
                Toast.makeText(applicationContext, R.string.copied_to_clipboard, Toast.LENGTH_SHORT)
                    .show()
            } catch (exception: Exception) {
                exception.printStackTrace()
                Toast.makeText(applicationContext, R.string.scan_failed, Toast.LENGTH_SHORT).show()
            }
        }

        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val EXTRA_TEXT = "extra_text"

        fun intent(context: Context, text: String): Intent {
            return Intent(context, CopyActivity::class.java).apply {
                putExtra(EXTRA_TEXT, text)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
            }
        }
    }
}
