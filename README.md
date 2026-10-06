# jaco

一个用 Java 21 从零手写的终端流式对话 agent（类 Claude Code 的 harness，v0.1 为纯聊天）。

## 运行

```bash
mvn package
java -jar target/jaco.jar
```

首次运行会提示创建 `~/.jaco/config.yaml`（可用 `JACO_HOME` 环境变量改位置）：

```yaml
active: deepseek
# system_prompt: 自定义系统提示词（可选）
# log_level: DEBUG（可选）
providers:
  deepseek:
    base_url: https://api.deepseek.com   # 根地址、.../v1 或完整 /chat/completions 均可
    api_key: ${DEEPSEEK_API_KEY}         # ${VAR} 占位符从环境变量替换
    model: deepseek-chat
    temperature: 0.7
```

任何 OpenAI 兼容服务（DeepSeek / GLM / Qwen / Kimi / Ollama 的 /v1 等）填三样即可接入。

## 交互

- 流式输出，等待首字节时有 spinner，每轮结束显示 token 用量与耗时
- **Ctrl+C** 中断正在生成的回复，已生成部分保留进会话
- `/help` `/new` `/exit`；会话自动落盘到 `~/.jaco/sessions/`，启动自动恢复最近一次
- 日志只写 `~/.jaco/logs/`（stdout 属于 TUI）

## 架构（分包即边界，llm/agent 不得依赖 tui）

```
tui     JLine 3 REPL：流式渲染、spinner、Ctrl+C、命令注册表
agent   AgentRunner：一轮的生命周期（hook → 请求 → 流消费 → 归档）
llm     手写 OpenAI 兼容 SSE 客户端：pull 模型流、重试、取消；全量消息模型
hook    AgentHook 生命周期钩子（权限/审计等未来能力的挂载点）
session 会话 JSON 落盘/恢复
config  YAML 配置 + ${ENV} 占位符 + 命名 profile
```

## 预留的扩展点（M1 工具调用接入时使用）

- `Message` 已含 `tool_calls` / `tool_call_id`（OpenAI 形状），协议模型无需重构
- `ChatStream` 的事件流天然支持在 `finish_reason == "tool_calls"` 时截获执行工具
- `AgentHook`：`onBeforeToolCall`（可否决，拒绝原因回喂模型）/ `onAfterToolCall` / `onStop` 签名已定
- 重试/错误策略已实现：连接错误与 429/5xx 指数退避重试，4xx 直接报错，流中断保留部分内容
