package com.pavelpapko.arroulette

import android.app.Activity
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ScreenshotSaver {
    fun capture(activity: Activity, onResult: (Result<String>) -> Unit) {
        val decorView = activity.window.decorView
        if (decorView.width <= 0 || decorView.height <= 0) {
            onResult(Result.failure(IllegalStateException("Экран ещё не готов")))
            return
        }

        val bitmap = Bitmap.createBitmap(decorView.width, decorView.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(
            activity.window,
            bitmap,
            { result ->
                if (result != PixelCopy.SUCCESS) {
                    bitmap.recycle()
                    onResult(Result.failure(IllegalStateException("Не удалось получить снимок: $result")))
                    return@request
                }
                Thread {
                    val saveResult = runCatching { saveBitmap(activity, bitmap) }
                    bitmap.recycle()
                    activity.runOnUiThread { onResult(saveResult) }
                }.start()
            },
            Handler(Looper.getMainLooper())
        )
    }

    private fun saveBitmap(activity: Activity, bitmap: Bitmap): String {
        val fileName = "AR_Roulette_${FILE_DATE_FORMAT.format(Date())}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/AR Рулетка")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = activity.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Не удалось создать файл изображения")
        try {
            resolver.openOutputStream(uri)?.use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)) {
                    "Не удалось записать изображение"
                }
            } ?: error("Не удалось открыть файл изображения")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return fileName
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private val FILE_DATE_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
}
