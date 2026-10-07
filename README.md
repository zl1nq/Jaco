# jaco

一个用 Java 21 从零手写的终端 agent（类 Claude Code 的 harness），带工具调用与权限确认。

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
# max_iterations: 25              # 单轮最大 LLM 迭代次数（可选）
# context_limit: 65536            # 上下文压缩触发基准 tokens（可选）
# max_tool_result_chars: 8000     # 单条工具输出回填上限（可选，超限落盘归档）
providers:
  deepseek:
    base_url: https://api.deepseek.com   # 根地址、.../v1 或完整 /chat/completions 均可
    api_key: ${DEEPSEEK_API_KEY}         # ${VAR} 占位符从环境变量替换
    model: deepseek-chat
    temperature: 0.7
```

任何 OpenAI 兼容服务（DeepSeek / GLM / Qwen / Kimi / Ollama 的 /v1 等）填三样即可接入。配置中的未知字段会被静默忽略（向前兼容），可选项见 `JacoConfig`。测试用 `mvn test`（JUnit 5）。

## 交互

- 流式输出，等待首字节时有 spinner；agent 多轮工具循环由 `AgentRunner` 驱动，TUI 只消费事件
- **Markdown 渲染**：按行状态机流式渲染（标题/列表/引用/链接/粗斜体/行内代码），代码块词法高亮（java/python/js/bash/json，其他语言原样）；**表格对齐渲染**（CJK 宽度感知，未闭合表格 flush 降级）；truecolor 自动探测，降级 256 色。渲染器输出结构化 Span（语义色），REPL 通过 ANSI 后端消费，为 GUI 后端铺路
- 内置工具：`read_file`（带行号、分页）、`write_file`、`list_dir`、`run_command`、`grep`、`recall`（检索本会话被压缩归档的历史：关键词/正则过滤，offset/limit 分页）
- **权限闸门**：只读工具自动放行；写文件/执行命令需确认 `y=本次 / a=本会话放行 / n=拒绝`（拒绝原因回喂模型）；危险命令黑名单无条件拒绝
- **沙箱**：文件工具限制在工作目录内，`workspace.extra_roots` 可加白名单
- `run_command`：shell 可配置（`shell: auto/bash/cmd/powershell`），超时 120s；输出读到 50k 上限即停止读取（防无限输出撑爆内存），保留末尾 50k 回填
- **Ctrl+C** 中断本轮：取消流、杀命令子进程、拒绝待确认操作，已生成部分保留进会话
- `/help` `/new` `/exit`；会话自动落盘到 `~/.jaco/sessions/`，启动自动恢复最近一次
- 单轮最大 LLM 迭代次数 `max_iterations`（默认 25）防失控
- **上下文压缩**：两道机制配合——
  - **单条闸门**（`AgentRunner`，结果入口处）：单条工具输出超过 `max_tool_result_chars`（默认 8000）即整条落盘归档，回填头尾预览并提示可用 `recall` 取回全文；任何时刻消息列表里的单条 tool_result 都有上界，不依赖压缩周期
  - **轮级压缩**（`ContextCompactor`，轮开始检查）：超过 `context_limit` 的 70% 触发（目标压到 40%），阶梯式——旧轮工具结果置一行摘要占位（工具名 + 体量 + 首行）→ 旧轮按**原始内容**整体归档 JSONL、原位置留每轮一行的骨架摘要标记 → LLM 摘要兜底；
    最近 2 轮完整保护，tool_calls/tool_result 配对原子不切断；归档保真（改写只作用于发送视图），`recall` 可检索到完整原文
  - **超限兜底**：provider 报上下文超限错误时，跳过触发阈值强制压缩后重试一次（估算与真实 token 偏差的保险）
- 日志只写 `~/.jaco/logs/`（stdout 属于 TUI）

## 架构（分包即边界，llm/agent 不得依赖 tui）

```
tui     JLine 3 REPL：消费 TurnEvent 渲染、确认交互、Ctrl+C、命令注册表
agent   AgentRunner：驱动工具循环，吐 TurnEvent 事件流（Delta/ToolCall*/ApprovalRequest/Done）；
        单条工具输出回填闸门（超限落盘归档）+ 上下文超限强制压缩重试
        ContextCompactor：轮级压缩阶梯（占位 → 保真归档 → 摘要兜底；轮开始触发，循环本体不感知）
llm     手写 OpenAI 兼容 SSE 客户端：pull 模型流、tool_calls 增量累积、重试、取消
tool    Tool 接口 + ToolRegistry（Schema/Handler 分离）+ 6 个内置工具（含 recall 检索归档）+ PermissionHook + 路径沙箱
render  MarkdownRenderer（按行状态机 + 表格缓冲对齐）+ CodeHighlighter + Span/Color/Theme（语义色，ANSI 后端可替换）+ DisplayWidth（CJK 宽度）
hook    AgentHook 生命周期钩子（权限/审计/续轮等能力的挂载点）
session 会话 JSON 落盘/恢复
config  YAML 配置 + ${ENV} 占位符 + 命名 profile + shell/迭代上限/上下文与工具输出上限/额外目录；未知字段容错
```

## 扩展点

- 新增工具 = 实现 `Tool` 接口 + `registry.register()`，循环零改动
- `AgentHook.onStop` 已接线：返回 true 可强制续轮（"目标闸门"的挂载点）
- 子 agent（`task` 工具模式）、系统提示词动态分节、渲染器 GUI 后端均已留好入口
