package com.snaptext.app.capture

import android.app.Activity
import android.content.Intent

object CaptureResultReceiver {
    var projectionData: Intent? = null
    var projectionResultCode: Int = Activity.RESULT_CANCELED

    fun clear() {
        projectionData = null
        projectionResultCode = Activity.RESULT_CANCELED
    }
}
