package com.nfctool

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.MifareClassic
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.nfctool.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private lateinit var binding: ActivityMainBinding
    private var nfcAdapter: NfcAdapter? = null
    private val isScanning = AtomicBoolean(false)
    private val isLoading = AtomicBoolean(false)
    private val isWaitingForCard = AtomicBoolean(false)
    private val cardLostDetected = AtomicBoolean(false)

    // 当前扫描的卡片UID和最后一次检测到的Tag（用于卡片重连）
    private var currentCardUid: String? = null
    private var currentCardType: String? = null
    private var currentSectorCount: Int = 0
    private var lastTag: Tag? = null

    // 震动和声音
    private var vibrator: Vibrator? = null
    private var toneGenerator: ToneGenerator? = null

    // 密钥总数（用于显示）
    private var totalKeyCount = 0
    private var isKeysLoaded = false

    // 每块密钥数量
    private val CHUNK_SIZE = 2000

    // 常用密钥优先测试
    private val priorityKeys = listOf(
        "FFFFFFFFFFFF",  // 出厂默认
        "A0A1A2A3A4A5",  // 常见公共交通
        "B0B1B2B3B4B5",
        "D3F7D3F7D3F7",  // NDEF
        "000000000000",
        "112233445566",
        "123456789ABC",
        "ABCDEF123456"
    )

    // 存储找到的密钥
    private val foundKeys = mutableMapOf<Int, SectorKey>()

    // 已测试的密钥块索引（用于断点续扫）
    private val testedChunkIndex = AtomicInteger(0)

    // 卡片丢失检测
    private val cardCheckHandler = Handler(Looper.getMainLooper())
    private var lastCardCheckTime = 0L
    private val CARD_TIMEOUT = 3000L // 3秒无响应认为卡片丢失

    private val cardCheckRunnable = object : Runnable {
        override fun run() {
            if (isScanning.get() && !cardLostDetected.get()) {
                val elapsed = System.currentTimeMillis() - lastCardCheckTime
                if (elapsed > CARD_TIMEOUT && lastCardCheckTime > 0) {
                    // 卡片丢失
                    onCardLost()
                } else {
                    cardCheckHandler.postDelayed(this, 500)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initNfc()
        initSoundAndVibrate()
        setupViews()
        countKeys()
    }

    // NfcAdapter.ReaderCallback 实现
    override fun onTagDiscovered(tag: Tag) {
        Log.d("NfcTool", "onTagDiscovered called!")
        lastCardCheckTime = System.currentTimeMillis()

        runOnUiThread {
            processTag(tag)
        }
    }

    private fun initSoundAndVibrate() {
        vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
        toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
    }

    private fun playDetectionFeedback() {
        vibrator?.let {
            if (it.hasVibrator()) {
                it.vibrate(200)
            }
        }
        toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
    }

    private fun playErrorFeedback() {
        vibrator?.let {
            if (it.hasVibrator()) {
                it.vibrate(longArrayOf(0, 200, 100, 200), -1)
            }
        }
        toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 300)
    }

    private fun initNfc() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            Toast.makeText(this, "设备不支持NFC", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        if (!nfcAdapter!!.isEnabled) {
            Toast.makeText(this, "请先开启NFC功能", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupViews() {
        binding.btnStartScan.setOnClickListener {
            if (isScanning.get()) {
                stopScan()
            } else if (!isKeysLoaded) {
                Toast.makeText(this, "密钥库加载中，请稍候...", Toast.LENGTH_SHORT).show()
            } else if (!nfcAdapter!!.isEnabled) {
                Toast.makeText(this, "请先开启NFC功能", Toast.LENGTH_LONG).show()
            } else if (isWaitingForCard.get()) {
                isWaitingForCard.set(false)
                binding.btnStartScan.text = "开始扫描"
                appendLog("已取消等待")
            } else {
                isWaitingForCard.set(true)
                binding.btnStartScan.text = "等待刷卡..."
                appendLog("请将M1卡靠近手机背面...")
            }
        }

        binding.btnRecords.setOnClickListener {
            startActivity(android.content.Intent(this, RecordsActivity::class.java))
        }

        binding.btnClearLog.setOnClickListener {
            binding.tvLog.text = ""
            foundKeys.clear()
            updateKeyDisplay()
            // 清除当前卡片的保存记录
            currentCardUid?.let { uid ->
                clearSavedProgress(uid)
            }
        }

        binding.tvKeyCount.text = "密钥库: 统计中..."
    }

    private fun enableReaderMode() {
        // 仅启用 NFC_A，Mifare Classic 基于 ISO14443-3A
        // 减少不必要的轮询可显著提高连接稳定性
        val flags = NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                    NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS

        val extras = Bundle()
        extras.putLong(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 2000L)

        nfcAdapter?.enableReaderMode(this, this, flags, extras)
        Log.d("NfcTool", "ReaderMode enabled (NFC_A only)")
    }

    private fun disableReaderMode() {
        nfcAdapter?.disableReaderMode(this)
        Log.d("NfcTool", "ReaderMode disabled")
    }

    // ============ 扫描记录存储 ============

    private fun getPrefs() = getSharedPreferences("nfc_scan_records", Context.MODE_PRIVATE)

    private fun saveProgress(uid: String, sectorKeys: Map<Int, SectorKey>, chunkIdx: Int) {
        val json = JSONObject().apply {
            put("chunkIndex", chunkIdx)
            val keysJson = JSONObject()
            sectorKeys.forEach { (sector, key) ->
                val keyJson = JSONObject()
                keyJson.put("keyA", key.keyA ?: "")
                keyJson.put("keyB", key.keyB ?: "")
                keysJson.put(sector.toString(), keyJson)
            }
            put("keys", keysJson)
        }
        getPrefs().edit().putString("card_$uid", json.toString()).apply()
        Log.d("NfcTool", "Saved progress for card $uid: chunk=$chunkIdx")
    }

    private fun loadProgress(uid: String): Pair<Map<Int, SectorKey>, Int>? {
        val jsonStr = getPrefs().getString("card_$uid", null) ?: return null

        return try {
            val json = JSONObject(jsonStr)
            val chunkIdx = json.getInt("chunkIndex")
            val keysJson = json.getJSONObject("keys")
            val sectorKeys = mutableMapOf<Int, SectorKey>()

            keysJson.keys().forEach { sectorStr ->
                val sector = sectorStr.toInt()
                val keyJson = keysJson.getJSONObject(sectorStr)
                val sectorKey = SectorKey(sector)
                val keyA = keyJson.getString("keyA")
                val keyB = keyJson.getString("keyB")
                if (keyA.isNotEmpty()) sectorKey.keyA = keyA
                if (keyB.isNotEmpty()) sectorKey.keyB = keyB
                sectorKeys[sector] = sectorKey
            }

            Log.d("NfcTool", "Loaded progress for card $uid: chunk=$chunkIdx, keys=${sectorKeys.size}")
            Pair(sectorKeys, chunkIdx)
        } catch (e: Exception) {
            Log.e("NfcTool", "Failed to load progress", e)
            null
        }
    }

    private fun clearSavedProgress(uid: String) {
        getPrefs().edit().remove("card_$uid").apply()
        Log.d("NfcTool", "Cleared progress for card $uid")
    }

    // ============ 密钥库 ============

    private fun countKeys() {
        if (isLoading.getAndSet(true)) return

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.Main) {
                    appendLog("正在统计密钥库...")
                }

                val count = withContext(Dispatchers.IO) {
                    var keyCount = priorityKeys.size
                    try {
                        val inputStream = assets.open("keys.txt")
                        val reader = BufferedReader(InputStreamReader(inputStream))
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            val key = line?.trim()?.uppercase()
                            if (!key.isNullOrBlank() && key.length == 12) {
                                keyCount++
                            }
                        }
                        reader.close()
                    } catch (e: Exception) {
                        Log.e("NfcTool", "读取密钥文件失败", e)
                    }
                    keyCount
                }

                totalKeyCount = count
                isKeysLoaded = true

                withContext(Dispatchers.Main) {
                    binding.tvKeyCount.text = "密钥库: $totalKeyCount 个密钥"
                    appendLog("密钥库统计完成: $totalKeyCount 个密钥，分块大小: $CHUNK_SIZE")
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendLog("密钥库统计失败: ${e.message}")
                    binding.tvKeyCount.text = "密钥库: 加载失败"
                }
            } finally {
                isLoading.set(false)
            }
        }
    }

    private fun loadKeyChunk(chunkIndex: Int): List<String> {
        val chunk = mutableListOf<String>()

        // 第一块包含优先密钥
        if (chunkIndex == 0) {
            chunk.addAll(priorityKeys)
        }

        // 计算需要从文件读取的密钥范围
        // 总密钥 = priorityKeys.size + 文件密钥数
        // 第0块: priorityKeys + 文件前(CHUNK_SIZE - priorityKeys.size)个
        // 其他块: 文件中对应范围的密钥

        val prioritySize = priorityKeys.size
        val keysPerChunk = CHUNK_SIZE

        // 计算在总密钥中的起始和结束位置
        val totalStartIndex = chunkIndex * keysPerChunk
        val totalEndIndex = totalStartIndex + keysPerChunk

        // 计算需要从文件读取的范围（跳过优先密钥）
        val fileStartIndex = if (totalStartIndex >= prioritySize) totalStartIndex - prioritySize else 0
        val fileEndIndex = totalEndIndex - prioritySize

        try {
            val inputStream = assets.open("keys.txt")
            val reader = BufferedReader(InputStreamReader(inputStream))
            var line: String?
            var fileKeyIndex = 0  // 文件中密钥的索引

            while (reader.readLine().also { line = it } != null) {
                val key = line?.trim()?.uppercase()
                if (!key.isNullOrBlank() && key.length == 12) {
                    // 检查是否在需要读取的范围内
                    if (fileKeyIndex >= fileStartIndex && fileKeyIndex < fileEndIndex) {
                        if (key !in priorityKeys) {
                            chunk.add(key)
                        }
                    }
                    fileKeyIndex++
                    if (fileKeyIndex >= fileEndIndex) break
                }
            }
            reader.close()
        } catch (e: Exception) {
            Log.e("NfcTool", "读取密钥块失败", e)
        }

        return chunk
    }

    private fun getTotalChunks(): Int {
        return (totalKeyCount + CHUNK_SIZE - 1) / CHUNK_SIZE
    }

    // ============ 生命周期 ============

    override fun onResume() {
        super.onResume()
        enableReaderMode()
    }

    override fun onPause() {
        super.onPause()
        if (!isScanning.get()) {
            disableReaderMode()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        toneGenerator?.release()
        toneGenerator = null
        cardCheckHandler.removeCallbacks(cardCheckRunnable)
    }

    // ============ 卡片处理 ============

    private fun processTag(tag: Tag) {
        lastCardCheckTime = System.currentTimeMillis()

        if (!isWaitingForCard.get() && !isScanning.get()) {
            appendLog("请先点击\"开始扫描\"按钮")
            return
        }

        val uid = tag.id?.let { bytesToHex(it) } ?: "未知"
        val techList = tag.techList.joinToString(", ")
        Log.d("NfcTool", "Tag UID: $uid, TechList: $techList")

        val mifareClassic = MifareClassic.get(tag)
        if (mifareClassic == null) {
            appendLog("检测到卡片，但非Mifare Classic卡")
            appendLog("UID: $uid")
            appendLog("支持的技术: $techList")
            return
        }

        // 卡片在扫描中重新靠近（卡片丢失后重新检测到）
        if (isScanning.get() && currentCardUid == uid && cardLostDetected.get()) {
            Log.d("NfcTool", "Card reconnect detected, resuming scan...")
            cardLostDetected.set(false)
            lastCardCheckTime = System.currentTimeMillis()
            lastTag = tag
            isWaitingForCard.set(false)
            // 重置扫描状态以便重新启动扫描
            isScanning.set(false)
            appendLog("卡片重新检测到，继续扫描...")
            // 从新Tag获取MifareClassic继续扫描
            val freshMifare = MifareClassic.get(tag)
            if (freshMifare != null) {
                startKeyCracking(freshMifare)
            } else {
                appendLog("非Mifare Classic卡，无法继续扫描")
                onCardLost()
            }
            return
        }

        // 如果是正在扫描中的同一张卡（正常保持中），更新时间戳即可
        if (isScanning.get() && currentCardUid == uid) {
            lastCardCheckTime = System.currentTimeMillis()
            return
        }

        playDetectionFeedback()
        isWaitingForCard.set(false)
        currentCardUid = uid
        currentCardType = getMifareType(mifareClassic.type)
        currentSectorCount = mifareClassic.sectorCount

        appendLog("══════════════════════════════")
        appendLog("检测到M1卡: $uid")
        appendLog("类型: $currentCardType")
        appendLog("扇区数: $currentSectorCount")
        appendLog("总块数: ${mifareClassic.blockCount}")

        // 检查是否有保存的进度
        val savedProgress = loadProgress(uid)
        if (savedProgress != null) {
            val (savedKeys, savedChunk) = savedProgress
            appendLog("发现上次扫描记录，将从第 ${savedChunk + 1} 块继续")
        }

        appendLog("══════════════════════════════")

        startKeyCracking(mifareClassic)
    }

    private fun onCardLost() {
        Log.d("NfcTool", "onCardLost called, cardLostDetected=${cardLostDetected.get()}")

        // 设置标志位
        cardLostDetected.set(true)

        // 停止扫描循环
        isScanning.set(false)
        cardCheckHandler.removeCallbacks(cardCheckRunnable)

        // 保存未完成的卡片记录
        currentCardUid?.let { uid ->
            currentCardType?.let { cardType ->
                CardRecordManager.saveCardRecord(
                    this,
                    uid,
                    cardType,
                    currentSectorCount,
                    foundKeys.toMap()
                )
            }
        }

        runOnUiThread {
            playErrorFeedback()
            appendLog("卡片已离开，扫描暂停")
            appendLog("请重新靠近卡片继续扫描")

            // 保存当前进度，从当前块继续
            currentCardUid?.let { uid ->
                val currentChunk = testedChunkIndex.get()
                saveProgress(uid, foundKeys, currentChunk)
                appendLog("进度已保存（将从块 ${currentChunk + 1} 继续）")
            }

            isWaitingForCard.set(true) // 等待重新刷卡
            binding.btnStartScan.text = "等待刷卡..."
            binding.btnStartScan.isEnabled = true
            binding.tvKeyCount.text = "卡片已离开 | 点击取消"
            binding.tvScanProgress.text = "等待重新刷卡..."
        }
    }

    private fun startKeyCracking(mifareClassic: MifareClassic) {
        if (!isKeysLoaded) {
            appendLog("密钥库未加载完成，请稍候...")
            return
        }

        if (isScanning.getAndSet(true)) {
            appendLog("正在扫描中，请稍候...")
            return
        }

        Log.d("NfcTool", "startKeyCracking: starting scan")

        // 重置卡片丢失检测
        cardLostDetected.set(false)
        lastCardCheckTime = System.currentTimeMillis()
        cardCheckHandler.postDelayed(cardCheckRunnable, 500)

        val uid = currentCardUid ?: "unknown"
        val sectorCount = mifareClassic.sectorCount

        // 加载保存的进度
        val savedProgress = loadProgress(uid)
        var startChunkIndex = 0

        if (savedProgress != null) {
            val (savedKeys, savedChunk) = savedProgress
            foundKeys.clear()
            foundKeys.putAll(savedKeys)
            startChunkIndex = savedChunk
            testedChunkIndex.set(savedChunk)
        } else {
            foundKeys.clear()
            for (sector in 0 until sectorCount) {
                foundKeys[sector] = SectorKey(sector)
            }
            testedChunkIndex.set(0)
        }

        updateKeyDisplay()
        binding.btnStartScan.text = "扫描中..."
        binding.btnStartScan.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 先连接卡片
                Log.d("NfcTool", "Attempting to connect Mifare...")
                try {
                    mifareClassic.connect()
                    Log.d("NfcTool", "Mifare connected successfully, isConnected=${mifareClassic.isConnected}")
                } catch (e: Exception) {
                    Log.e("NfcTool", "Failed to connect Mifare: ${e.message}", e)
                    cardLostDetected.set(true)
                    withContext(Dispatchers.Main) {
                        appendLog("连接卡片失败: ${e.message}")
                        onCardLost()
                    }
                    return@launch
                }

                // 再次检查连接状态
                if (!mifareClassic.isConnected) {
                    Log.e("NfcTool", "Mifare not connected after connect() call")
                    cardLostDetected.set(true)
                    withContext(Dispatchers.Main) {
                        appendLog("连接卡片失败: 连接状态异常")
                        onCardLost()
                    }
                    return@launch
                }

                Log.d("NfcTool", "Starting main scan loop, isScanning=${isScanning.get()}, cardLostDetected=${cardLostDetected.get()}")

                val totalChunks = getTotalChunks()
                var testedCount = 0
                val startTime = System.currentTimeMillis()

                // 使用标签便于卡片断开时跳出所有循环
                mainLoop@ for (chunkIndex in startChunkIndex until totalChunks) {
                    // 卡片断开后立即停止
                    if (cardLostDetected.get() || !isScanning.get()) {
                        Log.d("NfcTool", "Breaking mainLoop: cardLostDetected=${cardLostDetected.get()}, isScanning=${isScanning.get()}")
                        break@mainLoop
                    }

                    testedChunkIndex.set(chunkIndex)

                    // 先加载密钥块（IO操作），再更新UI
                    Log.d("NfcTool", "Loading key chunk ${chunkIndex + 1}/$totalChunks")
                    val keyChunk = loadKeyChunk(chunkIndex)
                    Log.d("NfcTool", "Loaded ${keyChunk.size} keys for chunk ${chunkIndex + 1}")

                    // 更新UI - 使用 runOnUiThread 避免阻塞
                    runOnUiThread {
                        appendLog("加载密钥块 ${chunkIndex + 1}/$totalChunks... (${keyChunk.size} 个密钥)")
                    }

                    Log.d("NfcTool", "Starting sector loop, sectorCount=$sectorCount, keyChunk.size=${keyChunk.size}")

                    sectorLoop@ for (sector in 0 until sectorCount) {
                        // 卡片断开后立即停止
                        if (cardLostDetected.get() || !isScanning.get()) {
                            Log.d("NfcTool", "Breaking at sector loop start: cardLostDetected=${cardLostDetected.get()}, isScanning=${isScanning.get()}")
                            break@mainLoop
                        }

                        val sectorKey = foundKeys[sector]!!

                        if (sectorKey.keyA != null && sectorKey.keyB != null) {
                            continue@sectorLoop
                        }

                        keyLoop@ for (key in keyChunk) {
                            // 卡片断开后立即停止
                            if (cardLostDetected.get() || !isScanning.get()) {
                                break@mainLoop
                            }

                            // 更新卡片检测时间
                            lastCardCheckTime = System.currentTimeMillis()

                            // 每50个密钥更新一次进度显示
                            if (testedCount % 50 == 0) {
                                val elapsed = (System.currentTimeMillis() - startTime) / 1000
                                val speed = if (elapsed > 0) testedCount / elapsed else 0
                                val foundCount = foundKeys.count { it.value.keyA != null || it.value.keyB != null }
                                val progressPercent = ((chunkIndex + 1) * 100 / totalChunks)

                                runOnUiThread {
                                    binding.progressBar.progress = progressPercent
                                    binding.tvScanProgress.text = "块 ${chunkIndex + 1}/$totalChunks | 扇区 $sector | 速度: ${speed}次/秒 | 已找到: $foundCount/$sectorCount"
                                }
                            }

                            if (sectorKey.keyA == null) {
                                testedCount++
                                lastCardCheckTime = System.currentTimeMillis()
                                when (val result = testKey(mifareClassic, sector, key, true)) {
                                    KeyTestResult.Success -> {
                                        sectorKey.keyA = key
                                        runOnUiThread {
                                            appendLog("✓ 扇区$sector 密钥A: $key")
                                            updateKeyDisplay()
                                        }
                                        saveProgress(uid, foundKeys, chunkIndex)
                                    }
                                    KeyTestResult.CardLost -> {
                                        Log.d("NfcTool", "CardLost detected in keyA test, breaking mainLoop")
                                        break@mainLoop
                                    }
                                    KeyTestResult.Failed -> {}
                                }
                            }

                            // 卡片断开后立即停止
                            if (cardLostDetected.get() || !isScanning.get()) {
                                Log.d("NfcTool", "Breaking mainLoop after keyA: cardLostDetected=${cardLostDetected.get()}, isScanning=${isScanning.get()}")
                                break@mainLoop
                            }

                            if (sectorKey.keyB == null) {
                                testedCount++
                                lastCardCheckTime = System.currentTimeMillis()
                                when (val result = testKey(mifareClassic, sector, key, false)) {
                                    KeyTestResult.Success -> {
                                        sectorKey.keyB = key
                                        runOnUiThread {
                                            appendLog("✓ 扇区$sector 密钥B: $key")
                                            updateKeyDisplay()
                                        }
                                        saveProgress(uid, foundKeys, chunkIndex)
                                    }
                                    KeyTestResult.CardLost -> {
                                        Log.d("NfcTool", "CardLost detected in keyB test, breaking mainLoop")
                                        break@mainLoop
                                    }
                                    KeyTestResult.Failed -> {}
                                }
                            }

                            if (sectorKey.keyA != null && sectorKey.keyB != null) {
                                break@keyLoop
                            }
                        }
                    }

                    // 每完成一块保存一次进度
                    saveProgress(uid, foundKeys, chunkIndex + 1)

                    // 卡片断开后立即停止
                    if (cardLostDetected.get() || !isScanning.get()) {
                        Log.d("NfcTool", "Breaking mainLoop after chunk: cardLostDetected=${cardLostDetected.get()}, isScanning=${isScanning.get()}")
                        break@mainLoop
                    }

                    // 更新进度
                    val elapsed = (System.currentTimeMillis() - startTime) / 1000
                    val speed = if (elapsed > 0) testedCount / elapsed else 0
                    val foundCount = foundKeys.count { it.value.keyA != null || it.value.keyB != null }
                    runOnUiThread {
                        val progress = ((chunkIndex + 1) * 100 / totalChunks)
                        binding.progressBar.progress = progress
                        binding.tvScanProgress.text = "块 ${chunkIndex + 1}/$totalChunks | 速度: ${speed}次/秒 | 已找到: $foundCount/$sectorCount"
                    }

                    val allFound = foundKeys.all { it.value.keyA != null && it.value.keyB != null }
                    if (allFound) {
                        runOnUiThread {
                            appendLog("所有扇区密钥已找到，提前结束扫描")
                        }
                        // 扫描完成，清除保存的记录
                        clearSavedProgress(uid)
                        break
                    }
                }

                Log.d("NfcTool", "Scan loop ended, cardLostDetected=${cardLostDetected.get()}")

                // 处理扫描结束后的状态
                if (cardLostDetected.get()) {
                    // 卡片断开，调用 onCardLost 更新 UI
                    onCardLost()
                } else {
                    // 正常扫描完成，保存卡片记录
                    val cardType = getMifareType(mifareClassic.type)
                    CardRecordManager.saveCardRecord(
                        this@MainActivity,
                        uid,
                        cardType,
                        sectorCount,
                        foundKeys.toMap()
                    )

                    val totalTime = (System.currentTimeMillis() - startTime) / 1000
                    runOnUiThread {
                        appendLog("══════════════════════════════")
                        appendLog("扫描完成!")
                        appendLog("共找到 ${foundKeys.count { it.value.keyA != null || it.value.keyB != null }} 个扇区密钥")
                        appendLog("耗时: ${totalTime}秒")
                        appendLog("记录已保存")
                        binding.btnStartScan.text = "开始扫描"
                        binding.btnStartScan.isEnabled = true
                        binding.progressBar.progress = 100
                        binding.tvKeyCount.text = "密钥库: $totalKeyCount 个密钥"
                        binding.tvScanProgress.text = "扫描完成 ✓"
                    }
                }

            } catch (e: Exception) {
                Log.e("NfcTool", "Exception in scan coroutine", e)
                cardLostDetected.set(true)
                onCardLost()
            } finally {
                isScanning.set(false)
                cardCheckHandler.removeCallbacks(cardCheckRunnable)
                try {
                    mifareClassic.close()
                } catch (e: Exception) {
                    Log.e("NfcTool", "关闭连接失败", e)
                }
            }
        }
    }

    // testKey 返回结果
    sealed class KeyTestResult {
        object Success : KeyTestResult()
        object Failed : KeyTestResult()
        object CardLost : KeyTestResult()
    }

    private fun testKey(mifare: MifareClassic, sector: Int, keyHex: String, isKeyA: Boolean): KeyTestResult {
        return try {
            // 先检查连接状态
            if (!mifare.isConnected) {
                Log.d("NfcTool", "testKey: Mifare not connected")
                cardLostDetected.set(true)
                return KeyTestResult.CardLost
            }

            val key = hexToBytes(keyHex)
            val authResult = if (isKeyA) {
                mifare.authenticateSectorWithKeyA(sector, key)
            } else {
                mifare.authenticateSectorWithKeyB(sector, key)
            }

            if (authResult) {
                // 认证成功，尝试读取块来验证密钥确实有效
                try {
                    val blockIndex = mifare.sectorToBlock(sector)
                    mifare.readBlock(blockIndex)
                    KeyTestResult.Success
                } catch (e: TagLostException) {
                    Log.d("NfcTool", "TagLostException during read: card lost")
                    cardLostDetected.set(true)
                    KeyTestResult.CardLost
                } catch (e: java.io.IOException) {
                    // 读取失败但认证成功，可能是卡片临时问题，仍然认为密钥正确
                    Log.d("NfcTool", "IOException during read after successful auth: ${e.message}")
                    KeyTestResult.Success
                }
            } else {
                KeyTestResult.Failed
            }
        } catch (e: TagLostException) {
            Log.d("NfcTool", "TagLostException: card lost")
            cardLostDetected.set(true)
            KeyTestResult.CardLost
        } catch (e: IllegalStateException) {
            // "Call connect() first!" - 连接已断开
            Log.d("NfcTool", "IllegalStateException: ${e.message}")
            cardLostDetected.set(true)
            KeyTestResult.CardLost
        } catch (e: java.io.IOException) {
            // IOException 通常表示卡片断开或通信失败
            Log.d("NfcTool", "IOException: ${e.message}")
            cardLostDetected.set(true)
            KeyTestResult.CardLost
        } catch (e: Exception) {
            // 其他异常，检查是否与卡片丢失相关
            Log.d("NfcTool", "Unexpected error in testKey: ${e.javaClass.simpleName} - ${e.message}")
            cardLostDetected.set(true)
            KeyTestResult.CardLost
        }
    }

    private fun stopScan() {
        isScanning.set(false)
        isWaitingForCard.set(false)
        cardCheckHandler.removeCallbacks(cardCheckRunnable)

        // 保存进度
        currentCardUid?.let { uid ->
            saveProgress(uid, foundKeys, testedChunkIndex.get())

            // 保存未完成的卡片记录
            currentCardType?.let { cardType ->
                CardRecordManager.saveCardRecord(
                    this,
                    uid,
                    cardType,
                    currentSectorCount,
                    foundKeys.toMap()
                )
            }
        }

        binding.btnStartScan.text = "开始扫描"
        binding.btnStartScan.isEnabled = true
        binding.tvKeyCount.text = "密钥库: $totalKeyCount 个密钥"
        binding.tvScanProgress.text = "准备就绪"
        appendLog("扫描已停止，进度已保存")
    }

    private fun updateKeyDisplay() {
        val sb = StringBuilder()
        foundKeys.forEach { (sector, key) ->
            sb.append("扇区 $sector: ")
            sb.append("A=${key.keyA ?: "未找到"} ")
            sb.append("B=${key.keyB ?: "未找到"}\n")
        }
        binding.tvKeys.text = sb.toString()
        // 自动滚动到底部
        binding.scrollViewKeys.post { binding.scrollViewKeys.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun appendLog(msg: String) {
        binding.tvLog.append("$msg\n")
        val scroll = binding.scrollView
        scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02X".format(it) }
    }

    private fun hexToBytes(hex: String): ByteArray {
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun getMifareType(type: Int): String {
        return when (type) {
            MifareClassic.TYPE_CLASSIC -> "Mifare Classic"
            MifareClassic.TYPE_PLUS -> "Mifare Plus"
            MifareClassic.TYPE_PRO -> "Mifare Pro"
            else -> "Unknown"
        }
    }
}
