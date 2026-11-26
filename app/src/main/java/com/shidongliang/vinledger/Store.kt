package com.shidongliang.vinledger

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 本地存储：记录与品牌前缀都存 SharedPreferences（JSON），最多保留 500 条 */
object Store {

    private const val PREFS = "vin_ledger"
    private const val KEY_RECORDS = "records"
    private const val KEY_BRANDS = "brands"
    private const val MAX_RECORDS = 500

    private lateinit var prefs: SharedPreferences

    var records: MutableList<ScanRecord> = mutableListOf()
        private set

    var brands: MutableMap<String, String> = mutableMapOf()
        private set

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        load()
    }

    // MARK: 记录

    fun add(
        content: String,
        source: String,
        symbology: String,
        direction: Direction?,
        timestamp: Long = System.currentTimeMillis()
    ): ScanRecord {
        val record = ScanRecord(
            id = UUID.randomUUID().toString(),
            content = content,
            timestamp = timestamp,
            source = source,
            symbology = symbology,
            direction = direction
        )
        records.add(0, record)
        if (records.size > MAX_RECORDS) {
            while (records.size > MAX_RECORDS) records.removeAt(records.size - 1)
        }
        save()
        return record
    }

    fun updateDirection(id: String, direction: Direction) {
        val index = records.indexOfFirst { it.id == id }
        if (index < 0) return
        records[index] = records[index].copy(direction = direction)
        save()
    }

    fun remove(id: String) {
        records.removeAll { it.id == id }
        save()
    }

    fun clearAll() {
        records.clear()
        save()
    }

    /** 几秒内重复扫到同一条（连拍抖动） */
    fun hasRecent(content: String, seconds: Long = 5): Boolean {
        val now = System.currentTimeMillis()
        return records.any { it.content == content && now - it.timestamp < seconds * 1000 }
    }

    /** 同内容 + 同方向是否已存在（排除正在补方向的那条） */
    fun hasSame(content: String, direction: Direction, excludeId: String?): Boolean =
        records.any { it.content == content && it.direction == direction && it.id != excludeId }

    fun hasAny(content: String): Boolean = records.any { it.content == content }

    // MARK: 品牌

    fun brandFor(content: String): String? {
        val prefix = VinUtil.prefixOf(content) ?: return null
        return brands[prefix] ?: BrandBook.builtin[prefix]
            ?: brands[prefix.take(3)] ?: BrandBook.builtin[prefix.take(3)]
    }

    fun addBrand(prefix: String, name: String) {
        brands[prefix.trim().uppercase()] = name.trim()
        save()
    }

    fun removeBrand(prefix: String) {
        brands.remove(prefix)
        save()
    }

    // MARK: 持久化

    private fun save() {
        if (!::prefs.isInitialized) return
        val array = JSONArray()
        records.forEach { r ->
            val obj = JSONObject()
            obj.put("id", r.id)
            obj.put("content", r.content)
            obj.put("timestamp", r.timestamp)
            obj.put("source", r.source)
            obj.put("symbology", r.symbology)
            obj.put("direction", r.direction?.name ?: "")
            array.put(obj)
        }
        val brandObj = JSONObject()
        brands.forEach { (k, v) -> brandObj.put(k, v) }
        prefs.edit().putString(KEY_RECORDS, array.toString()).putString(KEY_BRANDS, brandObj.toString()).apply()
    }

    private fun load() {
        records = mutableListOf()
        brands = mutableMapOf()

        val recordText = prefs.getString(KEY_RECORDS, null)
        if (!recordText.isNullOrEmpty()) {
            runCatching {
                val array = JSONArray(recordText)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val dirName = obj.optString("direction", "")
                    val direction = Direction.values().firstOrNull { it.name == dirName }
                    records.add(
                        ScanRecord(
                            id = obj.optString("id", UUID.randomUUID().toString()),
                            content = obj.optString("content", ""),
                            timestamp = obj.optLong("timestamp", 0L),
                            source = obj.optString("source", "相机"),
                            symbology = obj.optString("symbology", "未知"),
                            direction = direction
                        )
                    )
                }
            }
        }

        val brandText = prefs.getString(KEY_BRANDS, null)
        if (!brandText.isNullOrEmpty()) {
            runCatching {
                val obj = JSONObject(brandText)
                obj.keys().forEach { key -> brands[key] = obj.optString(key, "") }
            }
        }
    }
}
