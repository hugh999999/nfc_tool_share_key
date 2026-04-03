package com.nfctool

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nfctool.databinding.ActivityRecordsBinding
import java.io.File

/**
 * 扫描记录管理页面
 */
class RecordsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecordsBinding
    private lateinit var adapter: CardRecordAdapter
    private var records: MutableList<CardRecord> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecordsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViews()
        loadRecords()
    }

    override fun onResume() {
        super.onResume()
        loadRecords()
    }

    private fun setupViews() {
        // 返回按钮
        binding.btnBack.setOnClickListener {
            finish()
        }

        // 导出全部按钮
        binding.btnExportAll.setOnClickListener {
            exportAllRecords()
        }

        // 设置 RecyclerView
        adapter = CardRecordAdapter(records,
            onViewClick = { record -> showRecordDetail(record) },
            onExportClick = { record -> exportRecord(record) },
            onDeleteClick = { record -> confirmDelete(record) }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    private fun loadRecords() {
        records.clear()
        records.addAll(CardRecordManager.getAllCardRecords(this))

        // 更新 UI
        binding.tvRecordCount.text = "共 ${records.size} 条记录"
        binding.emptyState.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (records.isEmpty()) View.GONE else View.VISIBLE

        adapter.notifyDataSetChanged()
    }

    /**
     * 显示记录详情
     */
    private fun showRecordDetail(record: CardRecord) {
        val detailActivity = Intent(this, RecordDetailActivity::class.java)
        detailActivity.putExtra("uid", record.uid)
        startActivity(detailActivity)
    }

    /**
     * 导出单条记录
     */
    private fun exportRecord(record: CardRecord) {
        val options = arrayOf("JSON 格式", "文本格式", "MFD 格式")
        AlertDialog.Builder(this)
            .setTitle("选择导出格式")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        // JSON 格式
                        val content = CardRecordManager.exportAsJson(this, record.uid)
                        if (content != null) {
                            shareContent(content, "card_${record.uid}.json")
                        } else {
                            Toast.makeText(this, "导出失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                    1 -> {
                        // 文本格式
                        val content = CardRecordManager.exportAsText(this, record.uid)
                        if (content != null) {
                            shareContent(content, "card_${record.uid}.txt")
                        } else {
                            Toast.makeText(this, "导出失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                    2 -> {
                        // MFD 格式
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

    /**
     * 分享 MFD 文件
     */
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

    /**
     * 导出全部记录
     */
    private fun exportAllRecords() {
        if (records.isEmpty()) {
            Toast.makeText(this, "没有记录可导出", Toast.LENGTH_SHORT).show()
            return
        }

        val content = CardRecordManager.exportAllAsJson(this)
        if (content != null) {
            shareContent(content, "all_card_records.json")
        } else {
            Toast.makeText(this, "导出失败", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 确认删除
     */
    private fun confirmDelete(record: CardRecord) {
        AlertDialog.Builder(this)
            .setTitle("确认删除")
            .setMessage("确定要删除卡片 ${record.uid} 的记录吗？\n此操作不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                if (CardRecordManager.deleteCardRecord(this, record.uid)) {
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                    loadRecords()
                } else {
                    Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 分享内容
     */
    private fun shareContent(content: String, fileName: String) {
        try {
            // 保存到临时文件
            val file = File(cacheDir, fileName)
            file.writeText(content)

            // 使用 FileProvider 分享
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "NFC卡片密钥记录")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(shareIntent, "导出记录"))
        } catch (e: Exception) {
            // 如果 FileProvider 失败，直接分享文本
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, content)
                putExtra(Intent.EXTRA_SUBJECT, "NFC卡片密钥记录")
            }
            startActivity(Intent.createChooser(shareIntent, "导出记录"))
        }
    }

    /**
     * RecyclerView 适配器
     */
    class CardRecordAdapter(
        private val records: List<CardRecord>,
        private val onViewClick: (CardRecord) -> Unit,
        private val onExportClick: (CardRecord) -> Unit,
        private val onDeleteClick: (CardRecord) -> Unit
    ) : RecyclerView.Adapter<CardRecordAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvUid: TextView = view.findViewById(R.id.tvUid)
            val tvTime: TextView = view.findViewById(R.id.tvTime)
            val tvCardType: TextView = view.findViewById(R.id.tvCardType)
            val tvStatus: TextView = view.findViewById(R.id.tvStatus)
            val tvProgress: TextView = view.findViewById(R.id.tvProgress)
            val progressBar: ProgressBar = view.findViewById(R.id.progressBar)
            val btnView: Button = view.findViewById(R.id.btnView)
            val btnExport: Button = view.findViewById(R.id.btnExport)
            val btnDelete: Button = view.findViewById(R.id.btnDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_card_record, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val record = records[position]

            holder.tvUid.text = "UID: ${record.uid}"
            holder.tvTime.text = record.getFormattedTime()
            holder.tvCardType.text = record.cardType

            // 显示状态
            if (record.isComplete || record.isFullyScanned()) {
                holder.tvStatus.text = "已完成"
                holder.tvStatus.setTextColor(android.graphics.Color.parseColor("#388E3C"))
            } else {
                holder.tvStatus.text = "未完成"
                holder.tvStatus.setTextColor(android.graphics.Color.parseColor("#F57C00"))
            }

            val foundCount = record.getFoundKeyCount()
            holder.tvProgress.text = "已找到: $foundCount/${record.sectorCount} 扇区"

            // 计算进度百分比
            val progress = if (record.sectorCount > 0) {
                (foundCount * 100) / (record.sectorCount * 2) // A+B 共 2 个密钥
            } else 0
            holder.progressBar.progress = progress

            // 按钮点击事件
            holder.btnView.setOnClickListener { onViewClick(record) }
            holder.btnExport.setOnClickListener { onExportClick(record) }
            holder.btnDelete.setOnClickListener { onDeleteClick(record) }
        }

        override fun getItemCount() = records.size
    }
}
