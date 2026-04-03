package com.nfctool

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 卡片记录管理类
 * 负责卡片扫描记录的存储、读取、删除和导出
 */
object CardRecordManager {

    private const val TAG = "CardRecordManager"
    private const val PREFS_NAME = "nfc_card_records"
    private const val KEY_RECORD_LIST = "record_list"

    // 记录文件目录名
    private const val RECORDS_DIR = "card_records"

    /**
     * 保存卡片记录
     */
    fun saveCardRecord(
        context: Context,
        uid: String,
        cardType: String,
        sectorCount: Int,
        sectorKeys: Map<Int, SectorKey>,
        isComplete: Boolean = false
    ): Boolean {
        return try {
            // 检查是否已存在记录，如果存在则更新时间
            val existingRecord = getCardRecord(context, uid)
            val scanTime = existingRecord?.scanTime ?: System.currentTimeMillis()

            // 自动判断是否完成
            val complete = isComplete || sectorKeys.all { it.value.keyA != null && it.value.keyB != null }

            val record = CardRecord(uid, scanTime, cardType, sectorCount, sectorKeys, complete)

            // 保存记录到文件
            val recordsDir = File(context.filesDir, RECORDS_DIR)
            if (!recordsDir.exists()) {
                recordsDir.mkdirs()
            }

            val recordFile = File(recordsDir, "$uid.json")
            val json = recordToJson(record)
            FileWriter(recordFile).use { writer ->
                writer.write(json.toString(2))
            }

            // 更新记录列表
            updateRecordList(context, uid, scanTime, cardType, sectorCount, complete)

            Log.d(TAG, "Saved card record: $uid, complete=$complete")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save card record", e)
            false
        }
    }

    /**
     * 获取所有卡片记录列表（元数据）
     */
    fun getAllCardRecords(context: Context): List<CardRecord> {
        val records = mutableListOf<CardRecord>()
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val listJson = prefs.getString(KEY_RECORD_LIST, "[]") ?: "[]"
            val jsonArray = JSONArray(listJson)

            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                val uid = item.getString("uid")
                val scanTime = item.getLong("scanTime")
                val cardType = item.getString("cardType")
                val sectorCount = item.getInt("sectorCount")

                // 从文件加载完整数据
                val fullRecord = getCardRecord(context, uid)
                if (fullRecord != null) {
                    records.add(fullRecord)
                } else {
                    // 如果文件不存在，使用元数据创建简化记录
                    records.add(CardRecord(uid, scanTime, cardType, sectorCount, emptyMap()))
                }
            }

            // 按时间倒序排列
            records.sortedByDescending { it.scanTime }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load card records", e)
            records
        }
    }

    /**
     * 获取单个卡片记录
     */
    fun getCardRecord(context: Context, uid: String): CardRecord? {
        return try {
            val recordFile = File(context.filesDir, "$RECORDS_DIR/$uid.json")
            if (!recordFile.exists()) {
                return null
            }

            val json = JSONObject(recordFile.readText())
            jsonToRecord(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load card record: $uid", e)
            null
        }
    }

    /**
     * 删除卡片记录
     */
    fun deleteCardRecord(context: Context, uid: String): Boolean {
        return try {
            // 删除文件
            val recordFile = File(context.filesDir, "$RECORDS_DIR/$uid.json")
            if (recordFile.exists()) {
                recordFile.delete()
            }

            // 从列表中移除
            removeFromRecordList(context, uid)

            Log.d(TAG, "Deleted card record: $uid")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete card record", e)
            false
        }
    }

    /**
     * 导出卡片记录为 JSON 字符串
     */
    fun exportAsJson(context: Context, uid: String): String? {
        val record = getCardRecord(context, uid) ?: return null
        return recordToJson(record).toString(2)
    }

    /**
     * 导出卡片记录为文本格式
     */
    fun exportAsText(context: Context, uid: String): String? {
        val record = getCardRecord(context, uid) ?: return null
        return buildString {
            appendLine("═══════════════════════════════════════")
            appendLine("NFC M1 卡片密钥记录")
            appendLine("═══════════════════════════════════════")
            appendLine()
            appendLine("卡片 UID: ${record.uid}")
            appendLine("扫描时间: ${record.getFormattedTime()}")
            appendLine("卡片类型: ${record.cardType}")
            appendLine("扇区数量: ${record.sectorCount}")
            appendLine()
            appendLine("───────────────────────────────────────")
            appendLine("密钥信息:")
            appendLine("───────────────────────────────────────")

            for (sector in 0 until record.sectorCount) {
                val sectorKey = record.sectorKeys[sector]
                appendLine()
                appendLine("扇区 $sector:")
                appendLine("  密钥A: ${sectorKey?.keyA ?: "未找到"}")
                appendLine("  密钥B: ${sectorKey?.keyB ?: "未找到"}")
            }

            appendLine()
            appendLine("═══════════════════════════════════════")
            appendLine("已找到 ${record.getFoundKeyCount()} 个密钥")
            appendLine("完整扇区 ${record.getCompleteSectorCount()}/${record.sectorCount}")
            appendLine("═══════════════════════════════════════")
        }
    }

    /**
     * 导出卡片记录为 MFD 格式 (Mifare Classic Dump)
     * MFD 格式: 每个块 16 字节，按扇区顺序存储
     * 扇区尾块格式: [访问控制 4字节] [KeyA 6字节] [访问控制 4字节] [KeyB 6字节]
     * 注意: 由于我们没有数据块内容，只生成扇区尾块（Sector Trailer）
     */
    fun exportAsMfd(context: Context, uid: String): ByteArray? {
        val record = getCardRecord(context, uid) ?: return null

        // 计算总块数
        // Mifare Classic 1K: 16 扇区 * 4 块 = 64 块
        // Mifare Classic 4K: 40 扇区, 前 32 扇区 4 块, 后 8 扇区 16 块
        val totalBlocks = when (record.sectorCount) {
            16 -> 64   // 1K
            40 -> 256  // 4K
            else -> record.sectorCount * 4
        }

        val data = ByteArray(totalBlocks * 16)

        // 填充每个扇区的尾块（块 3, 7, 11, ...）
        for (sector in 0 until record.sectorCount) {
            val sectorKey = record.sectorKeys[sector]

            // 计算扇区尾块的索引
            val trailerBlockIndex = if (sector < 32) {
                sector * 4 + 3
            } else {
                // 4K 卡片的后 8 个扇区每扇区 16 块
                32 * 4 + (sector - 32) * 16 + 15
            }

            val offset = trailerBlockIndex * 16

            // 扇区尾块格式:
            // 字节 0-5:   Key A (如果未知则为 0xFF)
            // 字节 6-9:   访问控制 (默认值)
            // 字节 10-15: Key B (如果未知则为 0xFF)

            // Key A
            val keyA = sectorKey?.keyA
            if (keyA != null && keyA.length == 12) {
                val keyABytes = hexStringToByteArray(keyA)
                System.arraycopy(keyABytes, 0, data, offset, 6)
            } else {
                // 未找到密钥，填充 0xFF
                for (i in 0 until 6) {
                    data[offset + i] = 0xFF.toByte()
                }
            }

            // 访问控制 (默认值: FF 07 80 00)
            data[offset + 6] = 0xFF.toByte()
            data[offset + 7] = 0x07.toByte()
            data[offset + 8] = 0x80.toByte()
            data[offset + 9] = 0x00.toByte()

            // Key B
            val keyB = sectorKey?.keyB
            if (keyB != null && keyB.length == 12) {
                val keyBBytes = hexStringToByteArray(keyB)
                System.arraycopy(keyBBytes, 0, data, offset + 10, 6)
            } else {
                // 未找到密钥，填充 0xFF
                for (i in 0 until 6) {
                    data[offset + 10 + i] = 0xFF.toByte()
                }
            }
        }

        return data
    }

    /**
     * 导出 MFD 格式为十六进制字符串（用于分享）
     */
    fun exportAsMfdHex(context: Context, uid: String): String? {
        val mfdData = exportAsMfd(context, uid) ?: return null
        return mfdData.joinToString("") { "%02X".format(it) }
    }

    /**
     * 十六进制字符串转字节数组
     */
    private fun hexStringToByteArray(hex: String): ByteArray {
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    /**
     * 导出所有记录
     */
    fun exportAllAsJson(context: Context): String? {
        val records = getAllCardRecords(context)
        if (records.isEmpty()) return null

        val jsonArray = JSONArray()
        records.forEach { record ->
            jsonArray.put(recordToJson(record))
        }
        return jsonArray.toString(2)
    }

    /**
     * 更新记录列表
     */
    private fun updateRecordList(
        context: Context,
        uid: String,
        scanTime: Long,
        cardType: String,
        sectorCount: Int,
        isComplete: Boolean
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val listJson = prefs.getString(KEY_RECORD_LIST, "[]") ?: "[]"
        val jsonArray = JSONArray(listJson)

        // 检查是否已存在，存在则移除旧的
        var foundIndex = -1
        for (i in 0 until jsonArray.length()) {
            val item = jsonArray.getJSONObject(i)
            if (item.getString("uid") == uid) {
                foundIndex = i
                break
            }
        }
        if (foundIndex >= 0) {
            jsonArray.remove(foundIndex)
        }

        // 添加新记录
        val newItem = JSONObject().apply {
            put("uid", uid)
            put("scanTime", scanTime)
            put("cardType", cardType)
            put("sectorCount", sectorCount)
            put("isComplete", isComplete)
        }
        jsonArray.put(newItem)

        prefs.edit().putString(KEY_RECORD_LIST, jsonArray.toString()).apply()
    }

    /**
     * 从记录列表中移除
     */
    private fun removeFromRecordList(context: Context, uid: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val listJson = prefs.getString(KEY_RECORD_LIST, "[]") ?: "[]"
        val jsonArray = JSONArray(listJson)

        val newArray = JSONArray()
        for (i in 0 until jsonArray.length()) {
            val item = jsonArray.getJSONObject(i)
            if (item.getString("uid") != uid) {
                newArray.put(item)
            }
        }

        prefs.edit().putString(KEY_RECORD_LIST, newArray.toString()).apply()
    }

    /**
     * 将记录转换为 JSON
     */
    private fun recordToJson(record: CardRecord): JSONObject {
        return JSONObject().apply {
            put("uid", record.uid)
            put("scanTime", record.scanTime)
            put("cardType", record.cardType)
            put("sectorCount", record.sectorCount)
            put("isComplete", record.isComplete)

            val keysJson = JSONObject()
            record.sectorKeys.forEach { (sector, key) ->
                val keyJson = JSONObject().apply {
                    put("keyA", key.keyA ?: "")
                    put("keyB", key.keyB ?: "")
                }
                keysJson.put(sector.toString(), keyJson)
            }
            put("sectorKeys", keysJson)
        }
    }

    /**
     * 从 JSON 解析记录
     */
    private fun jsonToRecord(json: JSONObject): CardRecord {
        val uid = json.getString("uid")
        val scanTime = json.getLong("scanTime")
        val cardType = json.getString("cardType")
        val sectorCount = json.getInt("sectorCount")
        val isComplete = json.optBoolean("isComplete", false)

        val sectorKeys = mutableMapOf<Int, SectorKey>()
        val keysJson = json.getJSONObject("sectorKeys")
        keysJson.keys().forEach { sectorStr ->
            val sector = sectorStr.toInt()
            val keyJson = keysJson.getJSONObject(sectorStr)
            val sectorKey = SectorKey(sector)
            val keyA = keyJson.optString("keyA", "")
            val keyB = keyJson.optString("keyB", "")
            if (keyA.isNotEmpty()) sectorKey.keyA = keyA
            if (keyB.isNotEmpty()) sectorKey.keyB = keyB
            sectorKeys[sector] = sectorKey
        }

        return CardRecord(uid, scanTime, cardType, sectorCount, sectorKeys, isComplete)
    }
}
