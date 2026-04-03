package com.nfctool

/**
 * 扇区密钥数据模型
 */
data class SectorKey(
    val sector: Int,
    var keyA: String? = null,
    var keyB: String? = null
)

/**
 * 卡片扫描记录数据模型
 */
data class CardRecord(
    val uid: String,                    // 卡片唯一标识
    val scanTime: Long,                 // 扫描时间戳
    val cardType: String,               // 卡片类型 (如 "Mifare Classic 1K")
    val sectorCount: Int,               // 扇区数
    val sectorKeys: Map<Int, SectorKey>,// 密钥数据
    val isComplete: Boolean = false     // 扫描是否完成
) {
    /**
     * 获取已找到的密钥数量
     */
    fun getFoundKeyCount(): Int {
        return sectorKeys.count { it.value.keyA != null || it.value.keyB != null }
    }

    /**
     * 获取完整的扇区数（A+B都找到）
     */
    fun getCompleteSectorCount(): Int {
        return sectorKeys.count { it.value.keyA != null && it.value.keyB != null }
    }

    /**
     * 格式化扫描时间显示
     */
    fun getFormattedTime(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(scanTime))
    }

    /**
     * 检查是否所有密钥都已找到
     */
    fun isFullyScanned(): Boolean {
        return sectorKeys.all { it.value.keyA != null && it.value.keyB != null }
    }
}
