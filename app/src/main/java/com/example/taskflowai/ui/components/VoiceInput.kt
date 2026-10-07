package com.example.taskflowai.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

/**
 * 麦克风按钮:按住说话(脉冲动画提示正在倾听),松开后识别并回调结果。
 *
 * ── 识别引擎链(两级兜底)─────────────────────────────────────────────
 * 1. 系统识别器(SpeechRecognizer):正常设备首选,零成本、识别质量好;
 * 2. **Vosk 离线引擎**:系统识别器同步失败(如华为 EMUI 的 FakeRecognitionService
 *    拒绝三方绑定)或超时无响应时,自动标记系统引擎不可用并**无缝切换到本地引擎**;
 *    模型缺失时自动下载(约 42MB,仅一次),之后完全离线可用。
 * ─────────────────────────────────────────────────────────────────────
 *
 * 交互与线程:
 * - 长按/松开用 pointerInput 手势检测(刻意不用 IconButton:其 clickable 会
 *   消费手势,外层 detectTapGestures 收不到 onPress);
 * - 模型加载在 Default 线程池、下载在 IO 线程池,主线程零阻塞;
 * - RECORD_AUDIO 权限在按压时按需申请;页面离开时结束会话。
 */
@Composable
fun VoiceMicButton(
    onRecognized: (String) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isListening by remember { mutableStateOf(false) }
    var pendingStart by remember { mutableStateOf(false) }

    /** 引擎②:Vosk 离线识别(模型缺失则先下载,下载跑应用级协程不随页面取消) */
    fun beginVoskListening() {
        if (!VoskVoiceEngine.isModelReady(context)) {
            VoskVoiceEngine.downloadModelAsync(context) { msg -> onError(msg) }
            return
        }
        isListening = true // 立即给脉冲反馈(模型加载/引擎启动期间也算"在听")
        scope.launch {
            val loaded = VoskVoiceEngine.ensureModelLoaded(context.applicationContext)
            if (!loaded) {
                isListening = false
                onError("语音引擎加载失败,请重试")
                return@launch
            }
            val started = VoskVoiceEngine.startListening(
                context = context.applicationContext,
                onResult = { text -> isListening = false; onRecognized(text) },
                onError = { msg -> isListening = false; onError(msg) },
                onListeningChanged = { isListening = it },
            )
            if (!started) {
                isListening = false
                onError("语音识别启动失败")
            }
        }
    }

    /** 按需申请权限后真正开始一次识别(引擎链:系统识别器 → Vosk 离线) */
    fun beginListening() {
        if (isListening) return
        if (VoskVoiceEngine.isDownloading) {
            onError("语音模型下载中,请稍候")
            return
        }
        // 引擎①:系统识别器(此前未损坏时)
        if (!SpeechRecognizerHolder.systemBroken) {
            val dispatched = startSystemListening(
                context = context.applicationContext,
                onResult = { text -> isListening = false; onRecognized(text) },
                onError = { msg -> isListening = false; onError(msg) },
                onListeningChanged = { isListening = it },
            )
            if (dispatched) return
            // 同步失败:startSystemListening 已标记损坏,立即无缝落到引擎②
        }
        beginVoskListening()
    }

    // 权限按需申请:授予后自动开始一次识别
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && pendingStart) beginListening()
        pendingStart = false
    }

    // 脉冲动画:倾听中呼吸式缩放 + 呼吸式光晕
    val transition = rememberInfiniteTransition(label = "micPulse")
    val pulseScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.28f,
        animationSpec = infiniteRepeatable(tween(620), RepeatMode.Reverse),
        label = "micPulseScale",
    )
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.08f,
        animationSpec = infiniteRepeatable(tween(620), RepeatMode.Reverse),
        label = "micPulseAlpha",
    )

    Box(
        modifier = modifier.size(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        // 光晕(仅倾听中可见)
        if (isListening) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .graphicsLayer {
                        scaleX = pulseScale
                        scaleY = pulseScale
                        alpha = pulseAlpha
                    }
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }

        Box(
            modifier = Modifier
                .size(48.dp)
                .semantics {
                    role = Role.Button
                    contentDescription = "按住说话"
                }
                .pointerInput(enabled) {
                    detectTapGestures(
                        onPress = {
                            if (enabled && !isListening) {
                                val hasPermission = ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.RECORD_AUDIO,
                                ) == PackageManager.PERMISSION_GRANTED
                                if (hasPermission) {
                                    beginListening()
                                } else {
                                    pendingStart = true
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                            // 按住期间保持倾听;松开(或手势取消)即停止并等待结果
                            tryAwaitRelease()
                            SpeechRecognizerHolder.stop()
                            VoskVoiceEngine.stop()
                            if (isListening) isListening = false
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = null,
                tint = if (isListening) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/*
 * 系统 SpeechRecognizer 生命周期容器:识别器复用;离开页面统一销毁。
 * systemBroken:进程级"系统引擎已损坏"标记 —— 部分厂商服务被拒绑后既不报错也不回调,
 * 标记后后续按压直接走 Vosk,避免每次长按都白白失败一次。
 */
private object SpeechRecognizerHolder {

    private const val TAG = "VoiceInput"
    private const val WATCHDOG_TIMEOUT_MS = 5_000L

    /** 系统识别器是否已被判定不可用(同步异常 / 看门狗超时) */
    @Volatile
    var systemBroken: Boolean = false

    private var recognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var watchdog: Runnable? = null

    fun obtain(context: Context): SpeechRecognizer? {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return null
        return recognizer ?: SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
            .also { recognizer = it }
    }

    fun stop() {
        recognizer?.stopListening()
    }

    fun release() {
        disarmWatchdog()
        recognizer?.destroy()
        recognizer = null
    }

    fun armWatchdog(onTimeout: () -> Unit) {
        disarmWatchdog()
        Log.d(TAG, "watchdog armed")
        watchdog = Runnable {
            Log.w(TAG, "watchdog fired: recognition service silent")
            onTimeout()
        }.also { mainHandler.postDelayed(it, WATCHDOG_TIMEOUT_MS) }
    }

    fun disarmWatchdog() {
        watchdog?.let { mainHandler.removeCallbacks(it) }
        watchdog = null
    }
}

/**
 * 系统识别器路径。@return true = 已成功发出识别请求;
 * false = 同步不可用(已标记 [SpeechRecognizerHolder.systemBroken],调用方应立即切换 Vosk)。
 */
private fun startSystemListening(
    context: Context,
    onResult: (String) -> Unit,
    onError: (String) -> Unit,
    onListeningChanged: (Boolean) -> Unit,
): Boolean {
    val recognizer = try {
        SpeechRecognizerHolder.obtain(context)
    } catch (e: Exception) {
        null
    }
    if (recognizer == null) {
        SpeechRecognizerHolder.systemBroken = true
        return false
    }

    return try {
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                SpeechRecognizerHolder.disarmWatchdog()
                onListeningChanged(true)
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit

            override fun onError(error: Int) {
                SpeechRecognizerHolder.disarmWatchdog()
                onError(voiceErrorMessage(error))
            }

            override fun onResults(results: Bundle?) {
                SpeechRecognizerHolder.disarmWatchdog()
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (text.isNullOrBlank()) onError("没有听清,请重试") else onResult(text)
            }

            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        onListeningChanged(true)
        recognizer.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
        )
        // 看门狗:服务被拒绑后既不报错也不回调时,5 秒判定损坏并提示切换
        SpeechRecognizerHolder.armWatchdog {
            SpeechRecognizerHolder.systemBroken = true
            onListeningChanged(false)
            onError("系统语音服务不可用,已切换至离线识别,请再按一次麦克风")
        }
        Log.d("VoiceInput", "system recognizer dispatched")
        true
    } catch (e: Exception) {
        // 同步被拒(如华为 FakeRecognitionService 的 SecurityException):标记损坏,
        // 静默返回 false 由调用方无缝切到 Vosk,不打扰用户。
        Log.w("VoiceInput", "system recognizer unavailable: ${e.message}")
        SpeechRecognizerHolder.systemBroken = true
        onListeningChanged(false)
        false
    }
}

/** 识别错误码 → 用户可读文案 */
private fun voiceErrorMessage(error: Int): String = when (error) {
    SpeechRecognizer.ERROR_NO_MATCH,
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有听清,请重试"
    SpeechRecognizer.ERROR_AUDIO -> "录音出错,请检查麦克风"
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少录音权限"
    SpeechRecognizer.ERROR_NETWORK,
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音服务网络异常"
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务忙,请稍后重试"
    else -> "语音识别失败($error)"
}

/** 页面离开时释放识别器(保留 Vosk 模型缓存,只结束会话) */
@Composable
fun ReleaseVoiceRecognizerOnDispose() {
    DisposableEffect(Unit) {
        onDispose {
            SpeechRecognizerHolder.release()
            VoskVoiceEngine.release()
        }
    }
}
