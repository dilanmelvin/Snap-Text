package com.snaptext.app.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

data class TextBlock(
    val text: String,
    val bounds: Rect
)

object OcrEngine {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognize(bitmap: Bitmap): List<TextBlock> {
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = recognizer.process(image).await()

            val blocks = result.textBlocks
                .flatMap { it.lines }
                .mapNotNull { line ->
                    val text = line.text.trim()
                    val bounds = line.boundingBox
                    if (bounds != null && isUsefulText(text, bounds, bitmap.height)) {
                        TextBlock(text, bounds)
                    } else {
                        null
                    }
                }
            removeNearDuplicates(blocks)
        } catch (exception: Exception) {
            exception.printStackTrace()
            emptyList()
        }
    }

    private fun removeNearDuplicates(blocks: List<TextBlock>): List<TextBlock> {
        val accepted = mutableListOf<TextBlock>()
        blocks.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left })).forEach { block ->
            val normalizedText = normalize(block.text)
            val isDuplicate = accepted.any { existing ->
                val existingText = normalize(existing.text)
                val sameText = normalizedText == existingText ||
                    normalizedText.contains(existingText) ||
                    existingText.contains(normalizedText)
                sameText && Rect.intersects(existing.bounds, block.bounds)
            }
            if (!isDuplicate) {
                accepted.add(block)
            }
        }
        return accepted
    }

    private fun normalize(text: String): String {
        return text.lowercase()
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun isUsefulText(text: String, bounds: Rect, imageHeight: Int): Boolean {
        if (text.length < 2) return false
        if (bounds.top < imageHeight * STATUS_BAR_FILTER_RATIO) return false
        if (bounds.width() < MIN_TEXT_WIDTH_PX && text.length < MIN_SHORT_TEXT_LENGTH) return false
        if (NOISE_PATTERNS.any { it.matches(text) }) return false
        return true
    }

    private const val STATUS_BAR_FILTER_RATIO = 0.055f
    private const val MIN_TEXT_WIDTH_PX = 56
    private const val MIN_SHORT_TEXT_LENGTH = 5

    private val NOISE_PATTERNS = listOf(
        Regex("""^\d{1,2}:\d{2}\s*(am|pm)?$""", RegexOption.IGNORE_CASE),
        Regex("""^\d+\s*(sec|secs|min|mins|hr|hrs|h|m|s)$""", RegexOption.IGNORE_CASE),
        Regex("""^\d+(\.\d+)?\s*(kb|mb|gb)/?s?$""", RegexOption.IGNORE_CASE),
        Regex("""^[\d\s:/.-]+$"""),
        Regex("""^[a-z]$""", RegexOption.IGNORE_CASE)
    )
}
