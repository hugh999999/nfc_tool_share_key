package com.nfctool

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.nfctool.databinding.ActivityRecordDetailBinding
import java.io.File

/**
 * 记录详情页面
 */
class RecordDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecordDetailBinding
    private var record: CardRecord? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecordDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViews()
        loadRecord()
    }

    private fun setupViews() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnExport.setOnClickListener {
            exportRecord()
        }
    }

    private fun loadRecord() {
        val uid = intent.getStringExtra("uid") ?: run {
            finish()
            return
        }

        record = CardRecordManager.getCardRecord(this, uid)

        if (record == null) {
            Toast.makeText(this, "记录不存在", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        displayRecord(record!!)
    }

    private fun displayRecord(record: CardRecord) {
        binding.tvUid.text = "UID: ${record.uid}"
        binding.tvScanTime.text = "扫描时间: ${record.getFormattedTime()}"
        binding.tvCardType.text = "卡片类型: ${record.cardType}"
        binding.tvSectorCount.text = "扇区数: ${record.sectorCount}"

        val foundCount = record.getFoundKeyCount()
        val totalKeys = record.sectorCount * 2
        binding.tvKeyProgress.text = "已找到密钥: $foundCount/$totalKeys"

        // 显示密钥列表
        val sb = StringBuilder()
        for (sector in 0 until record.sectorCount) {
            val sectorKey = record.sectorKeys[sector]
            sb.append("扇区 $sector:\n")
            sb.append("  A: ${sectorKey?.keyA ?: "未找到"}\n")
            sb.append("  B: ${sectorKey?.keyB ?: "未找到"}\n\n")
        }
        binding.tvKeys.text = sb.toString()
    }

    private fun exportRecord() {
        val record = this.record ?: return

        val options = arrayOf("JSON 格式", "文本格式", "MFD 格式")
        android.app.AlertDialog.Builder(this)
            .setTitle("选择导出格式")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val content = CardRecordManager.exportAsJson(this, record.uid)
                        if (content != null) {
                            shareContent(content, "card_${record.uid}.json")
                        } else {
                            Toast.makeText(this, "导出失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                    1 -> {
                        val content = CardRecordManager.exportAsText(this, record.uid)
                        if (content != null) {
                            shareContent(content, "card_${record.uid}.txt")
                        } else {
                            Toast.makeText(this, "导出失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                    2 -> {
                        val mfdData = CardRecordManager.exportAsMfd(this, record.uid)
                        if (mfdData != null) {
                            shareMfdFile(mfdData, record.uid)
                        } else {
                            Toast.makeText(this, "导出失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .show()
    }

    private fun shareMfdFile(data: ByteArray, uid: String) {
        try {
            val file = File(cacheDir, "card_$uid.mfd")
            file.writeBytes(data)

            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "NFC卡片MFD数据")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(shareIntent, "导出 MFD 文件"))
        } catch (e: Exception) {
            Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareContent(content: String, fileName: String) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, content)
            putExtra(Intent.EXTRA_SUBJECT, "NFC卡片密钥记录")
        }
        startActivity(Intent.createChooser(shareIntent, "导出记录"))
    }
}
