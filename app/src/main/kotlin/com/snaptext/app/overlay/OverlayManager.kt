package com.snaptext.app.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.snaptext.app.R
import com.snaptext.app.ocr.TextBlock
import com.snaptext.app.utils.CopyActivity
import com.snaptext.app.utils.PermissionHelper

/**
 * Owns the full-screen overlay window.
 *
 * Two steps share one window so the transition is seamless:
 *  1. [showLoading] displays the frozen screenshot, dimmed, with the app icon
 *     and a spinner, so the underlying app looks paused while scanning.
 *  2. [show] fills in the recognized words and controls on the same window.
 *
 * Controls:
 *  - "Select all" sits at the top-left, always visible.
 *  - Close (✕) sits at the top-right.
 *  - "Copy" floats just above the current selection, like a text-selection popup.
 */
object OverlayManager {
    private const val AUTO_DISMISS_DELAY_MS = 30000L
    private const val LOADING_TIMEOUT_MS = 12000L

    private var overlayView: FrameLayout? = null
    private var overlayBitmap: Bitmap? = null
    private var lensView: LensSelectionView? = null
    private var loadingContent: View? = null
    private var copyButton: TextView? = null
    private var isShowing = false
    private var cancelled = false
    private var ownsBitmap = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var autoDismissRunnable: Runnable? = null

    /** Step 1: frozen screenshot + dim + spinner shown while scanning. */
    fun showLoading(context: Context, screenshot: Bitmap?) {
        if (!PermissionHelper.canDrawOverlay(context)) return
        if (screenshot == null || screenshot.isRecycled) return

        dismiss(context)
        cancelled = false
        ownsBitmap = false
        overlayBitmap = screenshot

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val params = buildLayoutParams()

        val container = newContainer(context)

        val lens = newLensView(context, screenshot, emptyList())
        lensView = lens
        container.addView(lens, matchParent())

        loadingContent = buildLoadingContent(context)
        container.addView(loadingContent, matchParent())

        addCloseButton(context, container)

        overlayView = container
        isShowing = true
        windowManager.addView(container, params)
        container.requestFocus()

        scheduleDismiss(context.applicationContext, LOADING_TIMEOUT_MS, cancel = true)
    }

    /** Step 2: fill in the recognized words and controls on the same window. */
    fun show(context: Context, textBlocks: List<TextBlock>, screenshot: Bitmap?) {
        if (cancelled) {
            cancelled = false
            screenshot?.recycle()
            dismiss(context)
            return
        }

        if (!PermissionHelper.canDrawOverlay(context)) {
            screenshot?.recycle()
            dismiss(context)
            Toast.makeText(context, R.string.overlay_permission_denied, Toast.LENGTH_SHORT).show()
            return
        }

        if (screenshot == null || screenshot.isRecycled) {
            dismiss(context)
            Toast.makeText(context, R.string.scan_failed, Toast.LENGTH_SHORT).show()
            return
        }

        if (textBlocks.isEmpty()) {
            dismiss(context)
            screenshot.recycle()
            Toast.makeText(context, R.string.no_text_found, Toast.LENGTH_SHORT).show()
            return
        }

        val container = overlayView
        val lens = lensView
        // Reuse the loading window when it is still up and showing this bitmap.
        if (isShowing && container != null && lens != null && overlayBitmap === screenshot) {
            ownsBitmap = true
            loadingContent?.let { container.removeView(it) }
            loadingContent = null
            lens.setWords(textBlocks)
            addSelectAllButton(context, container)
            addHint(context, container)
            addFloatingCopyButton(context, container)
            container.requestFocus()
            scheduleAutoDismiss(context.applicationContext)
            return
        }

        buildResultWindow(context, textBlocks, screenshot)
    }

    private fun buildResultWindow(context: Context, textBlocks: List<TextBlock>, screenshot: Bitmap) {
        dismiss(context)
        overlayBitmap = screenshot
        ownsBitmap = true

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val params = buildLayoutParams()

        val container = newContainer(context)
        val lens = newLensView(context, screenshot, textBlocks)
        lensView = lens
        container.addView(lens, matchParent())

        addCloseButton(context, container)
        addSelectAllButton(context, container)
        addHint(context, container)
        addFloatingCopyButton(context, container)

        overlayView = container
        isShowing = true
        windowManager.addView(container, params)
        container.requestFocus()
        scheduleAutoDismiss(context.applicationContext)
    }

    fun dismiss(context: Context) {
        try {
            autoDismissRunnable?.let { mainHandler.removeCallbacks(it) }
            autoDismissRunnable = null
            val view = overlayView ?: return
            if (isShowing) {
                val windowManager =
                    context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                windowManager.removeView(view)
            }
        } catch (exception: Exception) {
            exception.printStackTrace()
        } finally {
            if (ownsBitmap) {
                overlayBitmap?.recycle()
            }
            overlayBitmap = null
            overlayView = null
            lensView = null
            loadingContent = null
            copyButton = null
            isShowing = false
            ownsBitmap = false
        }
    }

    private fun cancelLoading(context: Context) {
        cancelled = true
        dismiss(context)
    }

    // region view builders

    private fun newContainer(context: Context): FrameLayout {
        return FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            isFocusableInTouchMode = true
        }
    }

    private fun newLensView(
        context: Context,
        screenshot: Bitmap,
        words: List<TextBlock>
    ): LensSelectionView {
        return LensSelectionView(context, screenshot, words).apply {
            onInteraction = { scheduleAutoDismiss(context.applicationContext) }
            onSelectionChanged = { selectedText, rect ->
                if (selectedText != null && rect != null) {
                    positionCopyButton(context, rect)
                } else {
                    copyButton?.visibility = View.GONE
                }
                scheduleAutoDismiss(context.applicationContext)
            }
        }
    }

    private fun buildLoadingContent(context: Context): View {
        val loading = FrameLayout(context).apply {
            setBackgroundColor(Color.argb(120, 0, 0, 0))
            isClickable = true
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val iconSize = dp(context, 72)
        content.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_snaptext_logo)
        }, LinearLayout.LayoutParams(iconSize, iconSize))
        content.addView(ProgressBar(context), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(context, 20) })
        content.addView(TextView(context).apply {
            text = context.getString(R.string.scanning)
            setTextColor(Color.WHITE)
            textSize = 15f
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(context, 14) })

        loading.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER })
        return loading
    }

    private fun addCloseButton(context: Context, container: FrameLayout) {
        val closeButton = TextView(context).apply {
            text = "✕"
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(160, 0, 0, 0))
            }
            isClickable = true
            setOnClickListener { cancelLoading(context) }
        }
        val size = dp(context, 44)
        val params = FrameLayout.LayoutParams(size, size).apply {
            gravity = Gravity.TOP or Gravity.END
            rightMargin = dp(context, 16)
            topMargin = dp(context, 28)
        }
        container.addView(closeButton, params)
    }

    private fun addSelectAllButton(context: Context, container: FrameLayout) {
        val selectAll = buildPillButton(context, context.getString(R.string.select_all)) {
            lensView?.selectAll()
        }.apply {
            background = pillBackground(Color.argb(210, 45, 51, 62))
        }
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = dp(context, 16)
            topMargin = dp(context, 28)
        }
        container.addView(selectAll, params)
    }

    private fun addHint(context: Context, container: FrameLayout) {
        val hint = TextView(context).apply {
            text = context.getString(R.string.selection_hint)
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(Color.WHITE)
            val paddingH = dp(context, 14)
            val paddingV = dp(context, 8)
            setPadding(paddingH, paddingV, paddingH, paddingV)
            background = pillBackground(Color.argb(150, 0, 0, 0))
        }
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(context, 40)
        }
        container.addView(hint, params)
    }

    private fun addFloatingCopyButton(context: Context, container: FrameLayout) {
        val copy = buildPillButton(context, context.getString(R.string.copy)) {
            val text = lensView?.currentSelectedText()
            if (text.isNullOrBlank()) {
                Toast.makeText(context, R.string.no_text_found, Toast.LENGTH_SHORT).show()
                return@buildPillButton
            }
            // Copy via a focused activity so the clipboard write works on every
            // device (overlay windows are blocked on some OEM builds).
            val appContext = context.applicationContext
            appContext.startActivity(CopyActivity.intent(appContext, text))
            dismiss(context)
        }.apply {
            visibility = View.GONE
        }
        copyButton = copy
        container.addView(copy, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP or Gravity.START })
    }

    /** Places the Copy button just above the selection (or below if near the top). */
    private fun positionCopyButton(context: Context, rect: RectF) {
        val container = overlayView ?: return
        val copy = copyButton ?: return

        val width = dp(context, 108)
        val height = dp(context, 46)
        val gap = dp(context, 10)
        val margin = dp(context, 16)
        val topSafe = dp(context, 84)

        val screenWidth = if (container.width > 0) {
            container.width
        } else {
            context.resources.displayMetrics.widthPixels
        }
        val screenHeight = if (container.height > 0) {
            container.height
        } else {
            context.resources.displayMetrics.heightPixels
        }

        val left = (rect.centerX() - width / 2f).toInt()
            .coerceIn(margin, (screenWidth - width - margin).coerceAtLeast(margin))
        var top = (rect.top - height - gap).toInt()
        if (top < topSafe) {
            top = (rect.bottom + gap).toInt()
        }
        top = top.coerceIn(topSafe, (screenHeight - height - dp(context, 24)).coerceAtLeast(topSafe))

        copy.layoutParams = FrameLayout.LayoutParams(width, height).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = left
            topMargin = top
        }
        copy.visibility = View.VISIBLE
        copy.bringToFront()
    }

    private fun buildPillButton(
        context: Context,
        label: String,
        onClick: () -> Unit
    ): TextView {
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 15f
            setTextColor(Color.WHITE)
            val paddingH = dp(context, 22)
            val paddingV = dp(context, 11)
            setPadding(paddingH, paddingV, paddingH, paddingV)
            background = pillBackground(Color.rgb(30, 116, 245))
            elevation = dp(context, 6).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    // endregion

    private fun matchParent() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT
    )

    private fun buildLayoutParams(): WindowManager.LayoutParams {
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
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        return params
    }

    private fun pillBackground(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 100f
            setColor(color)
        }
    }

    private fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }

    private fun scheduleAutoDismiss(context: Context) {
        scheduleDismiss(context, AUTO_DISMISS_DELAY_MS, cancel = false)
    }

    private fun scheduleDismiss(context: Context, delayMs: Long, cancel: Boolean) {
        autoDismissRunnable?.let { mainHandler.removeCallbacks(it) }
        autoDismissRunnable = Runnable {
            if (cancel) cancelLoading(context) else dismiss(context)
        }
        mainHandler.postDelayed(autoDismissRunnable!!, delayMs)
    }
}
