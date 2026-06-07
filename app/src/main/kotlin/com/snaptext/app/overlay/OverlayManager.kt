package com.snaptext.app.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import com.snaptext.app.R
import com.snaptext.app.ocr.TextBlock
import com.snaptext.app.utils.ClipboardHelper
import com.snaptext.app.utils.PermissionHelper

object OverlayManager {
    private const val AUTO_DISMISS_DELAY_MS = 12000L

    private var overlayView: View? = null
    private var overlayBitmap: Bitmap? = null
    private var isShowing = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var autoDismissRunnable: Runnable? = null

    fun show(context: Context, textBlocks: List<TextBlock>, screenshot: Bitmap?) {
        if (!PermissionHelper.canDrawOverlay(context)) {
            screenshot?.recycle()
            Toast.makeText(context, R.string.overlay_permission_denied, Toast.LENGTH_SHORT).show()
            return
        }

        dismiss(context)

        if (textBlocks.isEmpty()) {
            screenshot?.recycle()
            Toast.makeText(context, R.string.no_text_found, Toast.LENGTH_SHORT).show()
            return
        }

        overlayBitmap = screenshot

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        val container = FrameLayout(context).apply {
            setBackgroundColor(context.getColor(R.color.overlay_bg))
            isClickable = true
            setOnClickListener { dismiss(context) }
        }

        textBlocks.forEach { block ->
            val blockView = View.inflate(context, R.layout.view_text_block, null)
            blockView.contentDescription = block.text
            blockView.setOnClickListener {
                ClipboardHelper.copy(context, block.text)
                Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
                dismiss(context)
            }

            val blockParams = FrameLayout.LayoutParams(
                (block.bounds.width() + 10).coerceAtLeast(48),
                (block.bounds.height() + 8).coerceAtLeast(22)
            ).apply {
                leftMargin = (block.bounds.left - 5).coerceAtLeast(0)
                topMargin = (block.bounds.top - 4).coerceAtLeast(0)
            }

            container.addView(blockView, blockParams)
        }

        overlayView = container
        windowManager.addView(container, params)
        isShowing = true
        scheduleAutoDismiss(context.applicationContext)
    }

    fun dismiss(context: Context) {
        try {
            autoDismissRunnable?.let { mainHandler.removeCallbacks(it) }
            autoDismissRunnable = null
            val view = overlayView ?: return
            if (isShowing) {
                val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                windowManager.removeView(view)
            }
        } catch (exception: Exception) {
            exception.printStackTrace()
        } finally {
            overlayBitmap?.recycle()
            overlayBitmap = null
            overlayView = null
            isShowing = false
        }
    }

    private fun scheduleAutoDismiss(context: Context) {
        autoDismissRunnable?.let { mainHandler.removeCallbacks(it) }
        autoDismissRunnable = Runnable {
            dismiss(context)
        }
        mainHandler.postDelayed(autoDismissRunnable!!, AUTO_DISMISS_DELAY_MS)
    }
}
