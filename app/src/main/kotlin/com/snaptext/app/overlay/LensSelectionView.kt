package com.snaptext.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.snaptext.app.ocr.TextBlock
import kotlin.math.max
import kotlin.math.min

/**
 * Google Lens style selection surface.
 *
 * The captured screenshot is drawn into this view and highlights share the SAME
 * bitmap -> view transform, so a highlight always lands exactly on the text the
 * user sees.
 *
 * Selection:
 *  - Tap selects a single word.
 *  - Press and drag extends the selection across words and lines.
 *  - "Select all" selects everything.
 *
 * The highlight is drawn as ONE continuous bar per line covering the selected
 * span (spaces included). Copying joins words on a line with spaces and
 * separates lines with newlines.
 */
@SuppressLint("ViewConstructor")
class LensSelectionView(
    context: Context,
    private val bitmap: Bitmap,
    initialWords: List<TextBlock> = emptyList()
) : View(context) {

    /**
     * Invoked with the joined selected text and the selection's bounding
     * rectangle in view coordinates, or (null, null) when nothing is selected.
     */
    var onSelectionChanged: ((String?, RectF?) -> Unit)? = null

    /** Invoked on any user touch so callers can reset auto-dismiss timers. */
    var onInteraction: (() -> Unit)? = null

    // Expected in reading order (see OcrEngine.buildReadingOrder).
    private var words: List<TextBlock> = initialWords

    private var scaleX = 1f
    private var scaleY = 1f

    // Current selection as an inclusive reading-order range; -1 means none.
    private var selLow = -1
    private var selHigh = -1
    private var anchorIndex = -1

    private var downX = 0f
    private var downY = 0f
    private var isDragging = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val density = resources.displayMetrics.density

    private val scrimPaint = Paint().apply {
        color = Color.argb(55, 0, 0, 0)
    }
    // Clear box on every detected word so it is obvious each word is selectable.
    private val idleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(45, 130, 180, 255)
    }
    private val idleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
        color = Color.argb(205, 150, 195, 255)
    }
    private val selectedFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(140, 66, 133, 244)
    }
    private val selectedStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.argb(255, 66, 133, 244)
    }

    private val cornerRadius = 6f * density
    private val highlightPadX = 3f * density
    private val highlightPadY = 2f * density

    private val srcRect = Rect()
    private val dstRect = RectF()
    private val barRect = RectF()

    init {
        isClickable = true
        isFocusable = false
    }

    fun setWords(newWords: List<TextBlock>) {
        words = newWords
        clearSelection()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bitmap.isRecycled) return

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return

        scaleX = viewWidth / bitmap.width
        scaleY = viewHeight / bitmap.height

        srcRect.set(0, 0, bitmap.width, bitmap.height)
        dstRect.set(0f, 0f, viewWidth, viewHeight)
        canvas.drawBitmap(bitmap, srcRect, dstRect, null)
        canvas.drawRect(dstRect, scrimPaint)

        if (words.isEmpty()) return

        // A clear box on every word; selected words get a stronger highlight.
        words.forEachIndexed { index, word ->
            val b = word.bounds
            barRect.set(
                b.left * scaleX - highlightPadX,
                b.top * scaleY - highlightPadY,
                b.right * scaleX + highlightPadX,
                b.bottom * scaleY + highlightPadY
            )
            if (index in selLow..selHigh) {
                canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, selectedFillPaint)
                canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, selectedStrokePaint)
            } else {
                canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, idleFillPaint)
                canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, idleStrokePaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (words.isEmpty()) return true
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                onInteraction?.invoke()
                downX = event.x
                downY = event.y
                isDragging = false
                val idx = findWordAt(event.x / scaleX, event.y / scaleY)
                if (idx >= 0) {
                    anchorIndex = idx
                    selLow = idx
                    selHigh = idx
                } else {
                    anchorIndex = -1
                    selLow = -1
                    selHigh = -1
                }
                invalidate()
                notifySelection()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (anchorIndex < 0) return true
                if (!isDragging) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (dx * dx + dy * dy > touchSlop * touchSlop) {
                        isDragging = true
                    }
                }
                if (isDragging) {
                    val idx = findNearestWord(event.x / scaleX, event.y / scaleY)
                    if (idx >= 0) {
                        val newLow = min(anchorIndex, idx)
                        val newHigh = max(anchorIndex, idx)
                        if (newLow != selLow || newHigh != selHigh) {
                            selLow = newLow
                            selHigh = newHigh
                            invalidate()
                            notifySelection()
                        }
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                onInteraction?.invoke()
                if (selLow >= 0) {
                    performClick()
                }
                notifySelection()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun selectAll() {
        onInteraction?.invoke()
        if (words.isEmpty()) return
        anchorIndex = 0
        selLow = 0
        selHigh = words.size - 1
        invalidate()
        notifySelection()
    }

    fun clearSelection() {
        selLow = -1
        selHigh = -1
        anchorIndex = -1
        invalidate()
        onSelectionChanged?.invoke(null, null)
    }

    fun currentSelectedText(): String? {
        if (selLow < 0 || selHigh < selLow || words.isEmpty()) return null

        val builder = StringBuilder()
        var previousLineId = words[selLow].lineId
        for (index in selLow..selHigh) {
            val word = words[index]
            if (index > selLow) {
                if (word.lineId != previousLineId) {
                    builder.append('\n')
                    previousLineId = word.lineId
                } else {
                    builder.append(' ')
                }
            }
            builder.append(word.text.trim())
        }
        return builder.toString().trim().ifBlank { null }
    }

    private fun notifySelection() {
        onSelectionChanged?.invoke(currentSelectedText(), selectionViewRect())
    }

    /** Bounding rectangle of the current selection in view coordinates, or null. */
    private fun selectionViewRect(): RectF? {
        if (selLow < 0 || selHigh < selLow || words.isEmpty()) return null
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        for (index in selLow..selHigh) {
            val b = words[index].bounds
            left = min(left, b.left)
            top = min(top, b.top)
            right = max(right, b.right)
            bottom = max(bottom, b.bottom)
        }
        return RectF(left * scaleX, top * scaleY, right * scaleX, bottom * scaleY)
    }

    private fun findWordAt(bitmapX: Float, bitmapY: Float): Int {
        val tolerance = 8f
        words.forEachIndexed { index, word ->
            val b = word.bounds
            if (bitmapX >= b.left - tolerance && bitmapX <= b.right + tolerance &&
                bitmapY >= b.top - tolerance && bitmapY <= b.bottom + tolerance
            ) {
                return index
            }
        }
        return -1
    }

    /** Nearest word to the point; used during a drag so selection feels continuous. */
    private fun findNearestWord(bitmapX: Float, bitmapY: Float): Int {
        var bestIndex = -1
        var bestDistance = Float.MAX_VALUE
        words.forEachIndexed { index, word ->
            val b = word.bounds
            val clampedX = bitmapX.coerceIn(b.left.toFloat(), b.right.toFloat())
            val clampedY = bitmapY.coerceIn(b.top.toFloat(), b.bottom.toFloat())
            val dx = bitmapX - clampedX
            val dy = bitmapY - clampedY
            val distance = dx * dx + dy * dy
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }
        return bestIndex
    }
}
