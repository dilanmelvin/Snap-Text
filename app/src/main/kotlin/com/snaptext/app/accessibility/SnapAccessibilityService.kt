package com.snaptext.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.snaptext.app.R
import com.snaptext.app.ocr.OcrEngine
import com.snaptext.app.ocr.TextBlock
import com.snaptext.app.overlay.OverlayManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

class SnapAccessibilityService : AccessibilityService() {
    companion object {
        private var instance: SnapAccessibilityService? = null

        fun scanVisibleText(): Boolean {
            val service = instance ?: return false
            val root = service.rootInActiveWindow ?: return false
            val blocks = service.collectTextBlocks(root)
            if (blocks.isEmpty()) {
                service.scanScreenshotWithOcr()
            } else {
                OverlayManager.show(service.applicationContext, blocks, null)
            }
            return true
        }

        fun isRunning(): Boolean = instance != null
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val screenshotExecutor = Executors.newSingleThreadExecutor()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        serviceScope.cancel()
        screenshotExecutor.shutdown()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    private fun scanScreenshotWithOcr() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Toast.makeText(this, R.string.no_text_found, Toast.LENGTH_SHORT).show()
            return
        }

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            screenshotExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val hardwareBitmap = Bitmap.wrapHardwareBuffer(
                        screenshot.hardwareBuffer,
                        screenshot.colorSpace
                    )
                    screenshot.hardwareBuffer.close()

                    if (hardwareBitmap == null) {
                        serviceScope.launch {
                            Toast.makeText(
                                this@SnapAccessibilityService,
                                R.string.no_text_found,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        return
                    }

                    val bitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                    hardwareBitmap.recycle()

                    serviceScope.launch(Dispatchers.IO) {
                        val blocks = OcrEngine.recognize(bitmap)
                        launch(Dispatchers.Main) {
                            if (blocks.isEmpty()) {
                                bitmap.recycle()
                                Toast.makeText(
                                    this@SnapAccessibilityService,
                                    R.string.no_text_found,
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                OverlayManager.show(applicationContext, blocks, bitmap)
                            }
                        }
                    }
                }

                override fun onFailure(errorCode: Int) {
                    serviceScope.launch {
                        Toast.makeText(
                            this@SnapAccessibilityService,
                            R.string.no_text_found,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }

    private fun collectTextBlocks(root: AccessibilityNodeInfo): List<TextBlock> {
        val blocks = mutableListOf<TextBlock>()
        traverse(root, blocks)
        return removeOverlaps(blocks)
            .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
    }

    private fun traverse(node: AccessibilityNodeInfo, blocks: MutableList<TextBlock>) {
        val text = bestText(node)
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        if (text != null && isUseful(bounds, text)) {
            blocks.add(TextBlock(text, bounds))
        }

        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                traverse(child, blocks)
                child.recycle()
            }
        }
    }

    private fun bestText(node: AccessibilityNodeInfo): String? {
        val text = node.text?.toString()?.trim()
        val description = node.contentDescription?.toString()?.trim()
        return when {
            !text.isNullOrBlank() -> text
            !description.isNullOrBlank() -> description
            else -> null
        }
    }

    private fun isUseful(bounds: Rect, text: String): Boolean {
        if (text.length < 2) return false
        if (bounds.width() < 24 || bounds.height() < 14) return false
        if (bounds.left < 0 || bounds.top < 0) return false
        if (NOISE.any { it.matches(text) }) return false
        return true
    }

    private fun removeOverlaps(blocks: List<TextBlock>): List<TextBlock> {
        val accepted = mutableListOf<TextBlock>()
        blocks.forEach { block ->
            val duplicate = accepted.any { existing ->
                val sameText = existing.text.equals(block.text, ignoreCase = true)
                sameText && Rect.intersects(existing.bounds, block.bounds)
            }
            if (!duplicate) {
                accepted.add(block)
            }
        }
        return accepted
    }

    private val NOISE = listOf(
        Regex("""^\d{1,2}:\d{2}\s*(am|pm)?$""", RegexOption.IGNORE_CASE),
        Regex("""^[\d\s:/.-]+$"""),
        Regex("""^[a-z]$""", RegexOption.IGNORE_CASE)
    )
}
