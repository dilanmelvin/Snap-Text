package com.snaptext.app.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.snaptext.app.R
import com.snaptext.app.ocr.OcrEngine
import com.snaptext.app.overlay.OverlayManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScreenCaptureService : Service() {
    companion object {
        private const val CHANNEL_ID = "snaptext_capture_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "ACTION_START_CAPTURE"

        fun buildStartIntent(context: Context): Intent {
            return Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
            }
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startCaptureForeground()

        if (intent?.action == ACTION_START) {
            startCapture()
        }

        return START_NOT_STICKY
    }

    private fun startCapture() {
        val resultCode = CaptureResultReceiver.projectionResultCode
        val data = CaptureResultReceiver.projectionData

        if (resultCode != Activity.RESULT_OK || data == null) {
            CaptureResultReceiver.clear()
            stopSelf()
            return
        }

        try {
            val projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)

            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    stopCapture()
                }
            }
            projectionCallback = callback
            mediaProjection?.registerCallback(callback, Handler(Looper.getMainLooper()))

            val metrics = getDisplayMetrics()
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "SnapTextCapture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null
            )

            serviceScope.launch {
                delay(900)
                captureFrame(width, height)
            }
        } catch (exception: Exception) {
            exception.printStackTrace()
            Toast.makeText(this, R.string.scan_failed, Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    private suspend fun captureFrame(width: Int, height: Int) {
        val image = imageReader?.acquireLatestImage()
        if (image == null) {
            stopCapture()
            stopSelf()
            return
        }

        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width

            val bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
            bitmap.recycle()
            stopCapture()
            clearProjectionGrant()

            val textBlocks = OcrEngine.recognize(croppedBitmap)
            withContext(Dispatchers.Main) {
                OverlayManager.show(applicationContext, textBlocks, croppedBitmap)
            }
        } catch (outOfMemory: OutOfMemoryError) {
            outOfMemory.printStackTrace()
            withContext(Dispatchers.Main) {
                Toast.makeText(applicationContext, R.string.scan_failed, Toast.LENGTH_SHORT).show()
            }
        } catch (exception: Exception) {
            exception.printStackTrace()
            withContext(Dispatchers.Main) {
                Toast.makeText(applicationContext, R.string.scan_failed, Toast.LENGTH_SHORT).show()
            }
        } finally {
            image.close()
            stopSelf()
        }
    }

    private fun stopCapture() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null

        projectionCallback?.let { mediaProjection?.unregisterCallback(it) }
        projectionCallback = null
        mediaProjection?.stop()
        mediaProjection = null
    }

    private fun clearProjectionGrant() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            CaptureResultReceiver.clear()
        }
    }

    private fun startCaptureForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_snap_tile)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun getDisplayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopCapture()
        serviceScope.cancel()
        super.onDestroy()
    }
}
