package com.example.taskflowai.domain.tool.impl

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.example.taskflowai.domain.tool.Tool
import com.example.taskflowai.domain.tool.ToolContext
import com.example.taskflowai.domain.tool.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject

/**
 * 打开其他应用。
 *
 * 实现方式:通过 PackageManager 枚举启动器应用(中文名 → 包名),用其 launcher Intent 拉起。
 * - 启动其他应用的公开入口不需要任何权限;
 * - Android 11+ 的包可见性:Manifest 已声明 MAIN/LAUNCHER 的 <queries>(合规做法,
 *   无需 Play 受限的 QUERY_ALL_PACKAGES);
 * - 实际启动走 [ToolContext.startActivitySafely](自动 NEW_TASK + 异常兜底)。
 */
class OpenAppTool @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
) : Tool {

    override val name = "open_app"

    override val description = "打开手机上的其他应用,如 天气、微信、相机、设置。参数 app_name 为应用名称(可带「APP」字样)。"

    override val parametersSchema = """
        {
          "type": "object",
          "properties": {
            "app_name": { "type": "string", "description": "要打开的应用名称,如 天气、微信、相机" }
          },
          "required": ["app_name"]
        }
    """.trimIndent()

    override val requiredPermissions: List<String> = emptyList()

    override suspend fun execute(args: JSONObject, context: ToolContext): ToolResult {
        val rawName = args.optString("app_name").trim()
        if (rawName.isEmpty()) {
            return ToolResult.Failure("缺少参数:app_name")
        }

        val apps = installedLauncherApps()
        if (apps.isEmpty()) {
            return ToolResult.Failure("无法读取已安装应用列表")
        }

        val query = normalize(rawName)
        val match = findBest(query, apps)
        if (match == null) {
            val hints = apps
                .filter { it.label.lowercase().contains(query) || query.contains(it.label.lowercase()) }
                .take(5)
                .joinToString("、") { it.label }
            return ToolResult.Failure(
                if (hints.isEmpty()) "没有找到「$rawName」这个应用" else "没有找到「$rawName」,你是不是想打开:$hints"
            )
        }

        val launchIntent = appContext.packageManager.getLaunchIntentForPackage(match.packageName)
            ?: return ToolResult.Failure("「${match.label}」没有可启动的界面")

        return if (context.startActivitySafely(launchIntent)) {
            ToolResult.Success("已打开「${match.label}」")
        } else {
            ToolResult.Failure("打开「${match.label}」失败(可能被系统后台启动策略拦截)")
        }
    }

    private data class AppEntry(val packageName: String, val label: String)

    private fun installedLauncherApps(): List<AppEntry> {
        val packageManager = appContext.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(launcherIntent, 0)
        }
        return resolveInfos
            .mapNotNull { it.activityInfo }
            .filter { it.packageName != appContext.packageName } // 不通过工具"打开自己"
            .map { AppEntry(it.packageName, it.loadLabel(packageManager).toString()) }
            .distinctBy { it.packageName }
    }

    /** 归一化用户说法:去空格/小写/去掉"app、应用、软件"等后缀 */
    private fun normalize(name: String): String =
        name.lowercase()
            .replace(" ", "")
            .removeSuffix("app")
            .removeSuffix("应用")
            .removeSuffix("软件")

    /**
     * 匹配优先级:完全相等 > 标签以查询开头 > 查询以标签开头 > 包含;
     * 同级取标签更短者(更像"主应用")。
     */
    private fun findBest(query: String, apps: List<AppEntry>): AppEntry? {
        val normalized = apps.map { it to it.label.lowercase().replace(" ", "") }
        val byPriority: List<List<AppEntry>> = listOf(
            normalized.filter { (_, label) -> label == query }.map { it.first },
            normalized.filter { (_, label) -> label.startsWith(query) }.map { it.first },
            normalized.filter { (_, label) -> query.startsWith(label) }.map { it.first },
            normalized.filter { (_, label) -> label.contains(query) }.map { it.first },
        )
        return byPriority.firstOrNull { it.isNotEmpty() }
            ?.minByOrNull { it.label.length }
    }
}
