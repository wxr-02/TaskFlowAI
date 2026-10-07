package com.example.taskflowai

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.example.taskflowai.ui.navigation.AppNavHost
import com.example.taskflowai.ui.theme.TaskFlowTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 阶段 4:Activity 只负责挂载导航图,所有界面与逻辑都在 Compose/MVI 层。
 *
 * 附:支持 `adb shell am start -n ... --es prefill "指令"` 预填输入框,
 * 用于自动化测试/演示(adb 无法输入中文)。用 StateFlow 承接,
 * 同时处理 onCreate 与 onNewIntent(已在前台时 am start 会走 onNewIntent)。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val prefillInstruction = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prefillInstruction.value = intent?.getStringExtra(EXTRA_PREFILL)
        setContent {
            TaskFlowTheme {
                val prefill by prefillInstruction.collectAsState()
                AppNavHost(
                    prefillInstruction = prefill,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val prefill = intent.getStringExtra(EXTRA_PREFILL)
        Log.d("MainActivity", "onNewIntent prefill=$prefill")
        prefillInstruction.value = prefill
    }

    companion object {
        const val EXTRA_PREFILL = "prefill"
    }
}
