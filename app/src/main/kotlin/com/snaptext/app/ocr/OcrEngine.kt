package com.snaptext.app.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlin.math.max
import kotlin.math.min

/**
 * A single recognized word.
 *
 * [order] is the position of the word in natural reading order (line by line,
 * left to right) and [lineId] identifies the visual line it belongs to. Both
 * are used by the overlay to support contiguous range selection and to
 * reconstruct sentence / multi-line text when copying.
 */
data class TextBlock(
    val text: String,
    val bounds: Rect,
    val lineId: Int = 0,
    val order: Int = 0
)

object OcrEngine {

    // One recognizer per script. ML Kit does not have a single "all languages"
    // recognizer, so we run each script model and merge the results. The Latin
    // model also covers most European / Latin-based languages.
    private val recognizers: List<TextRecognizer> by lazy {
        listOf(
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
            TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        )
    }

    private val warmScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile
    private var warmedUp = false

    /**
     * Loads all script models ahead of time by running them once on a tiny
     * bitmap, so the first real scan doesn't pay the model-loading cost. Safe to
     * call repeatedly and from any thread; only the first call does work.
     */
    fun warmUp() {
        if (warmedUp) return
        warmedUp = true
        warmScope.launch {
            try {
                val tiny = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
                val image = InputImage.fromBitmap(tiny, 0)
                recognizers.forEach { recognizer ->
                    try {
                        recognizer.process(image).await()
                    } catch (exception: Exception) {
                        exception.printStackTrace()
                    }
                }
                tiny.recycle()
            } catch (exception: Exception) {
                exception.printStackTrace()
            }
        }
    }

    suspend fun recognize(bitmap: Bitmap): List<TextBlock> = coroutineScope {
        try {
            // Small text (usernames, captions) is hard at native resolution, so
            // upscale before OCR and map the detected boxes back afterwards.
            val scale = upscaleFactor(bitmap)
            val ocrBitmap = if (scale > 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt(),
                    (bitmap.height * scale).toInt(),
                    true
                )
            } else {
                bitmap
            }

            // Run every script recognizer concurrently; total latency is close to
            // the slowest model rather than the sum of all of them. Each uses its
            // own InputImage so there is no shared state between them.
            val perRecognizer = recognizers.map { recognizer ->
                async(Dispatchers.Default) {
                    try {
                        recognizer.process(InputImage.fromBitmap(ocrBitmap, 0)).await()
                    } catch (exception: Exception) {
                        exception.printStackTrace()
                        null
                    }
                }
            }.awaitAll()

            val words = perRecognizer
                .filterNotNull()
                .flatMap { it.textBlocks }
                .flatMap { it.lines }
                .flatMap { it.elements }
                .mapNotNull { element ->
                    val text = element.text.trim()
                    val box = element.boundingBox ?: return@mapNotNull null
                    val bounds = if (scale > 1f) {
                        Rect(
                            (box.left / scale).toInt(),
                            (box.top / scale).toInt(),
                            (box.right / scale).toInt(),
                            (box.bottom / scale).toInt()
                        )
                    } else {
                        box
                    }
                    if (isUsefulText(text, bounds, bitmap.height)) {
                        TextBlock(text, bounds)
                    } else {
                        null
                    }
                }

            if (ocrBitmap !== bitmap) {
                ocrBitmap.recycle()
            }

            val deduped = removeOverlappingDuplicates(words)
            buildReadingOrder(deduped)
        } catch (exception: Exception) {
            exception.printStackTrace()
            emptyList()
        }
    }

    private fun upscaleFactor(bitmap: Bitmap): Float {
        return when (max(bitmap.width, bitmap.height)) {
            in 0 until 2200 -> 2f
            in 2200 until 3200 -> 1.5f
            else -> 1f
        }
    }

    /**
     * Groups words into visual lines and returns them in reading order with a
     * stable [TextBlock.lineId] and [TextBlock.order] assigned.
     */
    private fun buildReadingOrder(words: List<TextBlock>): List<TextBlock> {
        if (words.isEmpty()) return emptyList()

        val sorted = words.sortedBy { it.bounds.top }
        val lines = mutableListOf<MutableList<TextBlock>>()
        val lineTops = mutableListOf<Int>()
        val lineBottoms = mutableListOf<Int>()

        for (word in sorted) {
            val wordRect = word.bounds
            var placedLine = -1
            for (index in lines.indices) {
                val top = max(lineTops[index], wordRect.top)
                val bottom = min(lineBottoms[index], wordRect.bottom)
                val overlap = bottom - top
                val minHeight = min(lineBottoms[index] - lineTops[index], wordRect.height())
                if (minHeight > 0 && overlap > 0.45f * minHeight) {
                    placedLine = index
                    break
                }
            }

            if (placedLine >= 0) {
                lines[placedLine].add(word)
                lineTops[placedLine] = min(lineTops[placedLine], wordRect.top)
                lineBottoms[placedLine] = max(lineBottoms[placedLine], wordRect.bottom)
            } else {
                lines.add(mutableListOf(word))
                lineTops.add(wordRect.top)
                lineBottoms.add(wordRect.bottom)
            }
        }

        val lineOrder = lines.indices.sortedBy { lineTops[it] }
        val result = ArrayList<TextBlock>(words.size)
        var order = 0
        lineOrder.forEachIndexed { lineId, originalIndex ->
            lines[originalIndex]
                .sortedBy { it.bounds.left }
                .forEach { word ->
                    result.add(word.copy(lineId = lineId, order = order))
                    order++
                }
        }
        return result
    }

    /**
     * Removes boxes that overlap heavily in space. Different script recognizers
     * frequently detect the same on-screen region, so we keep only one box per
     * region, preferring the longer (usually more complete) text.
     */
    private fun removeOverlappingDuplicates(blocks: List<TextBlock>): List<TextBlock> {
        val accepted = mutableListOf<TextBlock>()
        blocks
            .sortedWith(compareByDescending<TextBlock> { it.text.length }
                .thenByDescending { it.bounds.width().toLong() * it.bounds.height() })
            .forEach { block ->
                val isDuplicate = accepted.any { existing ->
                    overlapRatio(existing.bounds, block.bounds) > 0.55f
                }
                if (!isDuplicate) {
                    accepted.add(block)
                }
            }
        return accepted
    }

    /** Intersection area divided by the smaller box area (0f..1f). */
    private fun overlapRatio(a: Rect, b: Rect): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0f
        val intersection = (right - left).toFloat() * (bottom - top).toFloat()
        val areaA = a.width().toFloat() * a.height().toFloat()
        val areaB = b.width().toFloat() * b.height().toFloat()
        val smaller = min(areaA, areaB)
        if (smaller <= 0f) return 0f
        return intersection / smaller
    }

    private fun isUsefulText(text: String, bounds: Rect, imageHeight: Int): Boolean {
        if (text.isBlank()) return false
        if (bounds.top < imageHeight * STATUS_BAR_FILTER_RATIO) return false
        if (bounds.width() < MIN_TEXT_WIDTH_PX && text.length < MIN_SHORT_TEXT_LENGTH) return false
        if (NOISE_PATTERNS.any { it.matches(text) }) return false
        return true
    }

    private const val STATUS_BAR_FILTER_RATIO = 0.055f
    private const val MIN_TEXT_WIDTH_PX = 14
    private const val MIN_SHORT_TEXT_LENGTH = 2

    private val NOISE_PATTERNS = listOf(
        Regex("""^\d{1,2}:\d{2}\s*(am|pm)?$""", RegexOption.IGNORE_CASE),
        Regex("""^\d+\s*(sec|secs|min|mins|hr|hrs|h|m|s)$""", RegexOption.IGNORE_CASE),
        Regex("""^\d+(\.\d+)?\s*(kb|mb|gb)/?s?$""", RegexOption.IGNORE_CASE)
    )
}
