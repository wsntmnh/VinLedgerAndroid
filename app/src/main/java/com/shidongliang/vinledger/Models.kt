package com.shidongliang.vinledger

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 出入库方向 */
enum class Direction(val label: String) {
    IN("入库"),
    OUT("出库")
}

/** 一条扫码记录 */
data class ScanRecord(
    val id: String,
    val content: String,
    val timestamp: Long,
    val source: String,
    val symbology: String,
    val direction: Direction?
) {
    val timeText: String
        get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(timestamp))
}

object VinUtil {

    /** 从任意内容里提取 17 位车架号：清洗成字母数字后滑窗查找，且不含 I / O / Q */
    fun vinFrom(text: String): String? {
        val cleaned = text.uppercase(Locale.US).filter { it.isLetterOrDigit() }
        if (cleaned.length < 17) return null
        var i = 0
        while (i <= cleaned.length - 17) {
            val w = cleaned.substring(i, i + 17)
            if (w.none { it == 'I' || it == 'O' || it == 'Q' } &&
                w.any { it.isLetter() } && w.any { it.isDigit() }
            ) {
                return w
            }
            i++
        }
        return null
    }

    /** 车架号前 5 位 */
    fun prefixOf(content: String): String? = vinFrom(content)?.take(5)
}

/** 内置品牌表：前 5 位优先，认不出退回 3 位 WMI */
object BrandBook {
    val builtin = mapOf(
        "LSJEM" to "尚界",
        "LSJEK" to "荣威",
        "LSV" to "上汽大众",
        "LFV" to "一汽-大众",
        "LSG" to "上汽通用",
        "LSJ" to "上汽乘用车",
        "LGB" to "东风日产",
        "LGW" to "长城汽车",
        "LVG" to "广汽丰田",
        "LHG" to "广汽本田",
        "LFM" to "一汽丰田",
        "LZW" to "上汽通用五菱",
        "LVS" to "长安福特",
        "LDC" to "神龙汽车"
    )
}
