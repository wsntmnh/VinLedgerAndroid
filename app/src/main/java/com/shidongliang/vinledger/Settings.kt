package com.shidongliang.vinledger

import android.content.Context
import android.content.SharedPreferences

/** 水印文字档位：档位决定基础字号，倍率再整体缩放 */
enum class WatermarkSize(val label: String, val scale: Float) {
    SMALL("小", 0.75f),
    MEDIUM("中", 1.0f),
    LARGE("大", 1.3f),
    XLARGE("特大", 1.6f)
}

/** 水印文字颜色 */
enum class WatermarkColorOption(val label: String, val argb: Int) {
    WHITE("白色", 0xFFFFFFFF.toInt()),
    BLACK("黑色", 0xFF000000.toInt()),
    YELLOW("黄色", 0xFFFFEB3B.toInt()),
    RED("红色", 0xFFEF5350.toInt()),
    GREEN("绿色", 0xFF66BB6A.toInt())
}

/** 照片清晰度：maxSide = 0 表示不缩放 */
enum class PhotoQuality(val label: String, val maxSide: Int, val jpegQuality: Int) {
    LOW("低", 1280, 60),
    MEDIUM("中", 1920, 78),
    HIGH("高", 3000, 90),
    MAX("最高", 0, 98)
}

/** 设置存储，全部落在 SharedPreferences，和记录分开一个文件 */
object SettingsStore {

    private const val PREFS = "vin_settings"
    private const val KEY_AUTO_PHOTO = "auto_photo"
    private const val KEY_WM_SIZE = "wm_size"
    private const val KEY_WM_COLOR = "wm_color"
    private const val KEY_WM_SCALE = "wm_scale"
    private const val KEY_WM_LOCATION = "wm_location"
    private const val KEY_QUALITY = "photo_quality"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    var autoPhoto: Boolean
        get() = prefs?.getBoolean(KEY_AUTO_PHOTO, true) ?: true
        set(value) {
            prefs?.edit()?.putBoolean(KEY_AUTO_PHOTO, value)?.apply()
        }

    var watermarkSize: WatermarkSize
        get() = runCatching {
            WatermarkSize.valueOf(prefs?.getString(KEY_WM_SIZE, WatermarkSize.MEDIUM.name) ?: "MEDIUM")
        }.getOrDefault(WatermarkSize.MEDIUM)
        set(value) {
            prefs?.edit()?.putString(KEY_WM_SIZE, value.name)?.apply()
        }

    var watermarkColor: WatermarkColorOption
        get() = runCatching {
            WatermarkColorOption.valueOf(prefs?.getString(KEY_WM_COLOR, WatermarkColorOption.WHITE.name) ?: "WHITE")
        }.getOrDefault(WatermarkColorOption.WHITE)
        set(value) {
            prefs?.edit()?.putString(KEY_WM_COLOR, value.name)?.apply()
        }

    /** 0.6 ~ 2.0，和 iOS 那边一致 */
    var watermarkScale: Float
        get() = prefs?.getFloat(KEY_WM_SCALE, 1.0f) ?: 1.0f
        set(value) {
            prefs?.edit()?.putFloat(KEY_WM_SCALE, value.coerceIn(0.6f, 2.0f))?.apply()
        }

    var showLocationInWatermark: Boolean
        get() = prefs?.getBoolean(KEY_WM_LOCATION, false) ?: false
        set(value) {
            prefs?.edit()?.putBoolean(KEY_WM_LOCATION, value)?.apply()
        }

    var photoQuality: PhotoQuality
        get() = runCatching {
            PhotoQuality.valueOf(prefs?.getString(KEY_QUALITY, PhotoQuality.HIGH.name) ?: "HIGH")
        }.getOrDefault(PhotoQuality.HIGH)
        set(value) {
            prefs?.edit()?.putString(KEY_QUALITY, value.name)?.apply()
        }
}
