package com.example.taskflowai.di

import com.example.taskflowai.domain.tool.Tool
import com.example.taskflowai.domain.tool.impl.AddCalendarEventTool
import com.example.taskflowai.domain.tool.impl.CancelAlarmTool
import com.example.taskflowai.domain.tool.impl.OpenAppTool
import com.example.taskflowai.domain.tool.impl.QueryAlarmsTool
import com.example.taskflowai.domain.tool.impl.SearchWeatherTool
import com.example.taskflowai.domain.tool.impl.SendSmsTool
import com.example.taskflowai.domain.tool.impl.SetAlarmTool
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * 工具的多绑定注册:每个工具一行 @Binds @IntoSet,
 * [com.example.taskflowai.domain.tool.ToolRegistry] 注入 Set<Tool> 即拿到全部工具。
 *
 * 扩展方式:新建 XxxTool 类(实现 Tool)→ 这里加一行 → 完成(
 * Registry、LlmClient、UI、未来的 Agent 都不用改)。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ToolModule {

    @Binds
    @IntoSet
    abstract fun bindSetAlarmTool(tool: SetAlarmTool): Tool

    @Binds
    @IntoSet
    abstract fun bindCancelAlarmTool(tool: CancelAlarmTool): Tool

    @Binds
    @IntoSet
    abstract fun bindQueryAlarmsTool(tool: QueryAlarmsTool): Tool

    @Binds
    @IntoSet
    abstract fun bindOpenAppTool(tool: OpenAppTool): Tool

    @Binds
    @IntoSet
    abstract fun bindAddCalendarEventTool(tool: AddCalendarEventTool): Tool

    @Binds
    @IntoSet
    abstract fun bindSendSmsTool(tool: SendSmsTool): Tool

    @Binds
    @IntoSet
    abstract fun bindSearchWeatherTool(tool: SearchWeatherTool): Tool
}
