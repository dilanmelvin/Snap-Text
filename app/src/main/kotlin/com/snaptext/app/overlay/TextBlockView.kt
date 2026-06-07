package com.snaptext.app.overlay

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import com.snaptext.app.R
import com.snaptext.app.ocr.TextBlock

class TextBlockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {
    init {
        setBackgroundResource(R.drawable.bg_text_block)
        setTextColor(context.getColor(R.color.block_text))
        textSize = 14f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        minHeight = (28 * resources.displayMetrics.density).toInt()
        isClickable = true
        isFocusable = true
    }

    fun bind(block: TextBlock) {
        text = block.text
    }
}
