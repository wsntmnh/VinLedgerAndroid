package com.shidongliang.vinledger

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/** 拍照水印：解码 → 缩放 → 画水印 → 存相册 / 分享 */
object PhotoWatermark {

    /** 主线程之外调用。返回带水印的位图，失败返回 null */
    fun render(
        context: Context,
        source: File,
        vin: String,
        brand: String?,
        direction: String?,
        quality: PhotoQuality
    ): Bitmap? {
        val decoded = decodeScaled(source, quality.maxSide) ?: return null
        val mutable = decoded.copy(Bitmap.Config.ARGB_8888, true) ?: return null
        if (decoded !== mutable) decoded.recycle()

        val canvas = Canvas(mutable)
        val base = max(mutable.width, mutable.height) / 1000f
        val fontSize = 26f * base * SettingsStore.watermarkSize.scale * SettingsStore.watermarkScale
            .coerceIn(0.6f, 2.0f)

        val color = SettingsStore.watermarkColor.argb
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = fontSize.coerceAtLeast(12f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setShadowLayer(fontSize * 0.12f, 0f, fontSize * 0.06f, 0x99000000.toInt())
        }

        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
        val lines = mutableListOf(vin)
        val sub = listOfNotNull(
            brand?.takeIf { it.isNotBlank() },
            direction?.takeIf { it.isNotBlank() },
            stamp
        ).joinToString(" · ")
        lines.add(sub)
        if (SettingsStore.showLocationInWatermark) {
            lastLocationText(context)?.let { lines.add(it) }
        }

        val margin = max(mutable.width, mutable.height) * 0.028f
        val lineGap = paint.textSize * 0.42f
        val lineHeight = paint.textSize + lineGap
        val blockHeight = lineHeight * lines.size

        // 底部渐变遮罩，保证浅色背景下也能看清
        val scrimHeight = blockHeight + margin * 2
        val scrimTop = (mutable.height - scrimHeight).toInt().coerceAtLeast(0)
        val scrim = android.graphics.LinearGradient(
            0f, scrimTop.toFloat(), 0f, mutable.height.toFloat(),
            intArrayOf(0x00000000, 0x8C000000.toInt()),
            null,
            android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawRect(
            0f, scrimTop.toFloat(), mutable.width.toFloat(), mutable.height.toFloat(),
            Paint().apply { shader = scrim }
        )

        var y = mutable.height - margin - blockHeight + paint.textSize
        for (line in lines) {
            canvas.drawText(line, margin, y, paint)
            y += lineHeight
        }
        return mutable
    }

    /** 写入系统相册，返回 uri */
    fun saveToGallery(context: Context, bitmap: Bitmap): Uri? {
        val name = "VIN_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date()) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/车架号台账")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        runCatching {
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, SettingsStore.photoQuality.jpegQuality, out)
            }
        }.onFailure {
            resolver.delete(uri, null, null)
            return null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return uri
    }

    /** 分享图片：先落一个缓存文件，再走 FileProvider */
    fun shareImage(context: Context, bitmap: Bitmap, vin: String) {
        val dir = File(context.cacheDir, "exports")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "VIN_${System.currentTimeMillis()}.jpg")
        runCatching {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, SettingsStore.photoQuality.jpegQuality, out)
            }
        }.onFailure { return }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, vin)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享照片"))
    }

    private fun decodeScaled(file: File, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        if (maxSide > 0) {
            val longest = max(bounds.outWidth, bounds.outHeight)
            while (longest / (sample * 2) >= maxSide) sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bitmap = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null

        if (maxSide > 0) {
            val longest = max(bitmap.width, bitmap.height)
            if (longest > maxSide) {
                val ratio = maxSide.toFloat() / longest
                val target = Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * ratio).toInt().coerceAtLeast(1),
                    (bitmap.height * ratio).toInt().coerceAtLeast(1),
                    true
                )
                if (target !== bitmap) bitmap.recycle()
                bitmap = target
            }
        }
        return bitmap
    }

    /** 位置信息只用系统缓存，不主动拉起定位，没权限就返回 null */
    fun lastLocationText(context: Context): String? {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        return runCatching {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val location = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: return null
            String.format(Locale.US, "%.5f, %.5f", location.latitude, location.longitude)
        }.getOrNull()
    }
}
