package com.example.taskflowai.data.remote

/**
 * LLM 服务配置常量。
 *
 * 这里是"占位"版本:后续阶段会改为从 DataStore 读取(用户可在设置页填写),
 * 届时只需把 [NetworkModule][com.example.taskflowai.di.NetworkModule] 中的
 * 两个 @Named 提供方法改成读 DataStore 即可,其余代码不用动。
 *
 * ── 如何切换 DeepSeek / Qwen ──────────────────────────────────────────────
 * 1. 在 App 设置页选择服务商(自动预填对应 Base URL);改 [BASE_URL] 仅影响出厂默认值;
 * 2. 在设置页填写对应服务商的 API Key(不会进版本库);
 * 3. 把 [CHAT_MODEL] / [EMBEDDING_MODEL] 改成该服务商的模型名。
 *
 * 注意:DeepSeek 目前**不提供** embeddings 接口(调用 embed() 会 404),
 * 需要做 RAG 时请切到 Qwen(DashScope 兼容模式同时提供 chat 和 embeddings)。
 * ─────────────────────────────────────────────────────────────────────────
 */
object LlmConfig {

    // ====== 出厂默认 API Key(留空)======
    // ⚠️ 此处**故意留空**:为保护密钥,请勿把真实 Key 提交进版本库。
    // 运行时在 App【设置页】填写你的 API Key 即可(DataStore 持久化,不入库)。
    // 仅当你想修改"出厂默认值"时才在此填写。
    const val API_KEY = ""

    // ====== Base URL(OpenAI 兼容)======
    /** DeepSeek:支持 chat/completions,不支持 embeddings */
    const val DEEPSEEK_BASE_URL = "https://api.deepseek.com/"

    /** 阿里云百炼(通义千问)OpenAI 兼容模式:chat 和 embeddings 都支持 */
    const val QWEN_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/"

    /** 当前使用的服务商地址。切 Qwen 时改为 [QWEN_BASE_URL] 并同步更换 API_KEY */
    const val BASE_URL = DEEPSEEK_BASE_URL

    // ====== 模型名 ======
    /** DeepSeek 对话模型;切 Qwen 时用 "qwen-plus" / "qwen-max" */
    const val CHAT_MODEL = "deepseek-chat"

    /** 嵌入模型(DeepSeek 无此接口;Qwen 用 "text-embedding-v3") */
    const val EMBEDDING_MODEL = "text-embedding-v3"

    /** 对话温度:0 更确定、2 更发散。DeepSeek 建议范围 0~2 */
    const val DEFAULT_TEMPERATURE = 0.7
}
