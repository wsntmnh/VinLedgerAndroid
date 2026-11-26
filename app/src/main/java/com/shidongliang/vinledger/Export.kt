package com.shidongliang.vinledger

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class ExportRange(val label: String) {
    TODAY("今天"),
    WEEK("近 7 天"),
    MONTH("近 30 天"),
    ALL("全部")
}

enum class DirFilter(val label: String) {
    ALL("全部"),
    IN("入库"),
    OUT("出库")
}

enum class ExportFormat(val label: String, val ext: String) {
    TXT("TXT", "txt"),
    CSV("CSV", "csv"),
    JSON("JSON", "json")
}

/** 导出内容项目：车架号恒有，其余可选 */
data class ExportOptions(
    val brand: Boolean = true,
    val direction: Boolean = true,
    val time: Boolean = true,
    val source: Boolean = false,
    val symbology: Boolean = false
)

private fun startOfToday(): Long {
    val cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

fun filterRecords(
    list: List<ScanRecord>,
    range: ExportRange,
    dir: DirFilter,
    vinOnly: Boolean,
    prefix: String,
    keyword: String
): List<ScanRecord> {
    val now = System.currentTimeMillis()
    val start: Long = when (range) {
        ExportRange.TODAY -> startOfToday()
        ExportRange.WEEK -> now - 7L * 24 * 3600 * 1000
        ExportRange.MONTH -> now - 30L * 24 * 3600 * 1000
        ExportRange.ALL -> 0L
    }
    val key = keyword.trim().lowercase(Locale.CHINA)
    return list.filter { r ->
        if (r.timestamp < start) return@filter false
        when (dir) {
            DirFilter.IN -> if (r.direction != Direction.IN) return@filter false
            DirFilter.OUT -> if (r.direction != Direction.OUT) return@filter false
            DirFilter.ALL -> Unit
        }
        if (vinOnly && VinUtil.vinFrom(r.content) == null) return@filter false
        if (prefix != "全部") {
            val p = VinUtil.prefixOf(r.content) ?: return@filter false
            if (p != prefix) return@filter false
        }
        if (key.isNotEmpty()) {
            val brand = Store.brandFor(r.content) ?: ""
            val hit = r.content.lowercase().contains(key) ||
                brand.lowercase().contains(key) ||
                r.symbology.lowercase().contains(key)
            if (!hit) return@filter false
        }
        true
    }
}

private fun csvField(value: String): String {
    return if (value.contains(',') || value.contains('"') || value.contains('\n')) {
        "\"" + value.replace("\"", "\"\"") + "\""
    } else value
}

private fun vinOrDefault(record: ScanRecord): String = VinUtil.vinFrom(record.content) ?: record.content

private fun headerOf(options: ExportOptions): List<String> {
    val header = mutableListOf("车架号")
    if (options.brand) header.add("品牌")
    if (options.direction) header.add("入库/出库")
    if (options.time) header.add("扫描时间")
    if (options.source) header.add("来源")
    if (options.symbology) header.add("码制")
    return header
}

private fun rowOf(record: ScanRecord, options: ExportOptions): List<String> {
    val row = mutableListOf(vinOrDefault(record))
    if (options.brand) row.add(Store.brandFor(record.content) ?: "")
    if (options.direction) row.add(record.direction?.label ?: "未标记")
    if (options.time) row.add(record.timeText)
    if (options.source) row.add(record.source)
    if (options.symbology) row.add(record.symbology)
    return row
}

fun buildExport(records: List<ScanRecord>, format: ExportFormat, options: ExportOptions): String {
    if (records.isEmpty()) return ""
    val header = headerOf(options)
    val rows = records.map { rowOf(it, options) }
    return when (format) {
        ExportFormat.CSV -> buildString {
            append(header.map(::csvField).joinToString(","))
            append('\n')
            rows.forEach { append(it.map(::csvField).joinToString(",")); append('\n') }
            append("合计,${records.size} 条")
        }

        ExportFormat.TXT -> buildString {
            val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
            append("# 车架号台账导出\n")
            append("# 条数：${records.size} 条\n")
            append("# 导出时间：${formatter.format(Date())}\n")
            append("# " + header.joinToString("\t") + "\n")
            rows.forEach { append(it.joinToString("\t")); append('\n') }
            append("# 合计：${records.size} 条")
        }

        ExportFormat.JSON -> {
            val array = JSONArray()
            rows.forEach { row ->
                val obj = JSONObject()
                header.forEachIndexed { index, key -> obj.put(key, row.getOrElse(index) { "" }) }
                array.put(obj)
            }
            val root = JSONObject()
            root.put("total", records.size)
            root.put("items", array)
            root.toString(2)
        }
    }
}

fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText("车架号台账", text))
}

/** 写到缓存目录并调起系统分享（微信 / 文件 / 邮件都行） */
fun shareText(context: Context, text: String, fileName: String) {
    val dir = File(context.cacheDir, "exports")
    if (!dir.exists()) dir.mkdirs()
    val file = File(dir, fileName)
    file.writeText(text)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, fileName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "导出文件"))
}

/** 导入 iOS 端导出的 CSV：按表头识别列，返回新增条数 */
fun importCsv(text: String): Int {
    val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    if (lines.isEmpty()) return 0
    val header = parseCsvLine(lines.first())
    val vinIndex = header.indexOfFirst { it.contains("车架号") || it.contains("内容") }
    val dirIndex = header.indexOfFirst { it.contains("入库") }
    val timeIndex = header.indexOfFirst { it.contains("扫描时间") || it.contains("时间") }
    val sourceIndex = header.indexOfFirst { it.contains("来源") }
    val symbologyIndex = header.indexOfFirst { it.contains("码制") }
    if (vinIndex < 0) return 0

    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
    var added = 0
    lines.drop(1).forEach { line ->
        if (line.startsWith("合计")) return@forEach
        val cells = parseCsvLine(line)
        val content = cells.getOrNull(vinIndex)?.trim().orEmpty()
        if (content.isEmpty()) return@forEach
        if (Store.hasAny(content)) return@forEach
        val direction = cells.getOrNull(dirIndex)?.let {
            when {
                it.contains("入库") -> Direction.IN
                it.contains("出库") -> Direction.OUT
                else -> null
            }
        }
        val timestamp = cells.getOrNull(timeIndex)?.let {
            runCatching { formatter.parse(it.trim())?.time }.getOrNull()
        } ?: System.currentTimeMillis()
        Store.add(
            content = content,
            source = cells.getOrNull(sourceIndex)?.ifEmpty { "导入" } ?: "导入",
            symbology = cells.getOrNull(symbologyIndex)?.ifEmpty { "未知" } ?: "未知",
            direction = direction,
            timestamp = timestamp
        )
        added++
    }
    return added
}

private fun parseCsvLine(line: String): List<String> {
    val cells = mutableListOf<String>()
    val sb = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            c == '"' -> {
                if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                    sb.append('"'); i++
                } else {
                    inQuotes = !inQuotes
                }
            }
            c == ',' && !inQuotes -> {
                cells.add(sb.toString()); sb.clear()
            }
            else -> sb.append(c)
        }
        i++
    }
    cells.add(sb.toString())
    return cells
}
