package com.example.taskflowai.ui.components

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Vosk 离线语音识别引擎 —— "自带引擎"兜底方案。
 *
 * 背景:部分厂商(实测华为 EMUI)的系统识别服务对三方应用完全锁死
 * (`FakeRecognitionService` + BIND_VOICE_INTERACTION 限制),系统 SpeechRecognizer
 * 路径不可用。Vosk 完全离线、无需 Key/账号、不经过系统服务,长按说话交互完全自控。
 *
 * 模型:vosk-model-small-cn-0.22(约 42MB,首次使用时自动下载并解压到
 * 应用外部私有目录 Android/data/<pkg>/files/ 下,之后永久离线可用)。
 *
 * 线程模型:模型加载在 [Dispatchers.Default](约 1s,绝不占主线程);
 * 识别回调统一切回主线程;下载在 [Dispatchers.IO]。
 */
object VoskVoiceEngine {

    private const val TAG = "VoskEngine"

    const val MODEL_DIR_NAME = "vosk-model-small-cn-0.22"
    const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"
    private const val SAMPLE_RATE = 16_000f

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var isDownloading: Boolean = false
        private set

    private var model: Model? = null
    private var session: Session? = null

    /** 模型文件是否已就绪(解压完整) */
    fun isModelReady(context: Context): Boolean {
        val dir = modelDir(context)
        return File(dir, "am").exists() && File(dir, "conf").exists()
    }

    /**
     * 加载模型到内存(首次约 1s,在 Default 线程池执行,可安全从主线程协程调用)。
     * @return 是否加载成功(模型缺失/损坏返回 false)
     */
    suspend fun ensureModelLoaded(context: Context): Boolean =
        withContext(Dispatchers.Default) {
            if (model != null) return@withContext true
            try {
                model = Model(modelDir(context).absolutePath)
                Log.d(TAG, "model loaded")
                true
            } catch (e: Exception) {
                Log.w(TAG, "model load failed: ${e.message}")
                false
            }
        }

    /**
     * 开始一次"按住说话"识别会话(需先 [ensureModelLoaded])。
     * @return false 表示启动失败
     */
    fun startListening(
        context: Context,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        onListeningChanged: (Boolean) -> Unit,
    ): Boolean {
        session?.cancel()
        session = null

        return try {
            val recognizer = Recognizer(model ?: return false, SAMPLE_RATE)
            val speechService = SpeechService(recognizer, SAMPLE_RATE)
            // 说话中途的停顿会让 Vosk 先产出一次 onResult:累计拼接,避免丢句子
            val accumulated = StringBuilder()

            session = object : Session {
                override fun stop() {
                    speechService.stop() // stop 触发 onFinalResult(异步刷出最终结果)
                }

                override fun cancel() {
                    runCatching { speechService.stop() }
                    runCatching { speechService.shutdown() }
                }
            }

            speechService.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) = Unit

                override fun onResult(hypothesis: String?) {
                    parseText(hypothesis)?.let { accumulated.append(it) }
                }

                override fun onFinalResult(hypothesis: String?) {
                    val text = (accumulated.toString() + (parseText(hypothesis) ?: ""))
                        .replace(" ", "")
                    Log.d(TAG, "final: «$text»")
                    mainHandler.post {
                        session = null
                        onListeningChanged(false)
                        if (text.isBlank()) onError("没有听清,请重试") else onResult(text)
                    }
                    runCatching { speechService.shutdown() }
                }

                override fun onError(e: Exception?) {
                    Log.w(TAG, "recognition error: ${e?.message}")
                    mainHandler.post {
                        session = null
                        onListeningChanged(false)
                        onError("语音识别出错:${e?.message ?: "未知原因"}")
                    }
                    runCatching { speechService.shutdown() }
                }

                override fun onTimeout() {
                    mainHandler.post {
                        session = null
                        onListeningChanged(false)
                        onError("没有听清,请重试")
                    }
                    runCatching { speechService.shutdown() }
                }
            })

            mainHandler.post { onListeningChanged(true) }
            Log.d(TAG, "listening started")
            true
        } catch (e: Exception) {
            Log.w(TAG, "start failed: ${e.message}")
            false
        }
    }

    /** 松开按钮:停止录音,等待 Vosk 刷出最终结果(经 onFinalResult 回调) */
    fun stop() {
        session?.stop()
    }

    /** 页面销毁:结束当前会话(模型保留在内存,进程内持续复用) */
    fun release() {
        session?.cancel()
        session = null
    }

    /**
     * 在**应用级作用域**启动模型下载 —— 用户按了麦克风后又切页面/进设置,
     * 下载也不会被页面作用域取消。进度经 [onStatus](已切主线程)。
     */
    fun downloadModelAsync(context: Context, onStatus: (String) -> Unit) {
        if (isDownloading) return
        downloadScope.launch { downloadModel(context.applicationContext, onStatus) }
    }

    private val downloadScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO,
    )

    /**
     * 下载并解压离线模型(一次性,约 42MB)。挂起函数(内部切 IO 线程);
     * 进度经 [onStatus] 反馈(已切主线程),调用方转成用户提示。
     */
    suspend fun downloadModel(context: Context, onStatus: (String) -> Unit): Boolean {
        if (isDownloading) return false
        isDownloading = true
        val reportedPercents = mutableSetOf<Int>()
        val postStatus: (String) -> Unit = { message -> mainHandler.post { onStatus(message) } }

        return try {
            postStatus("开始下载离线语音模型(约 42MB,仅需一次)…")
            withContext(Dispatchers.IO) {
                val zipFile = File(context.cacheDir, "vosk-model.zip")

                val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 60_000
                }
                val total = connection.contentLengthLong
                connection.inputStream.use { input ->
                    zipFile.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) {
                                val percent = (downloaded * 100 / total).toInt()
                                if (percent in listOf(25, 50, 75) && reportedPercents.add(percent)) {
                                    postStatus("语音模型下载中…$percent%")
                                }
                            }
                        }
                    }
                }
                connection.disconnect()

                // 解压:zip 首层目录即 MODEL_DIR_NAME,按 entry 路径直接落盘
                ZipInputStream(zipFile.inputStream().buffered()).use { zipInput ->
                    var entry = zipInput.nextEntry
                    while (entry != null) {
                        val target = File(context.getExternalFilesDir(null), entry.name)
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { zipInput.copyTo(it) }
                        }
                        zipInput.closeEntry()
                        entry = zipInput.nextEntry
                    }
                }
                zipFile.delete()
            }

            val ok = isModelReady(context)
            postStatus(if (ok) "语音模型已就绪,长按麦克风说话" else "模型解压不完整,请重试")
            ok
        } catch (e: Exception) {
            Log.w(TAG, "download failed: ${e.message}")
            postStatus("模型下载失败,请检查网络后重试")
            false
        } finally {
            isDownloading = false
        }
    }

    private fun modelDir(context: Context): File =
        File(context.getExternalFilesDir(null), MODEL_DIR_NAME)

    private fun parseText(json: String?): String? =
        json?.let { runCatching { JSONObject(it).optString("text").ifBlank { null } }.getOrNull() }

    private interface Session {
        fun stop()
        fun cancel()
    }
}
