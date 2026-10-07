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
providers:
  deepseek:
    base_url: https://api.deepseek.com   # 根地址、.../v1 或完整 /chat/completions 均可
    api_key: ${DEEPSEEK_API_KEY}         # ${VAR} 占位符从环境变量替换
    model: deepseek-chat
    temperature: 0.7
```

任何 OpenAI 兼容服务（DeepSeek / GLM / Qwen / Kimi / Ollama 的 /v1 等）填三样即可接入。

## 交互

- 流式输出，等待首字节时有 spinner；agent 多轮工具循环由 `AgentRunner` 驱动，TUI 只消费事件
- 内置工具：`read_file`（带行号、分页）、`write_file`、`list_dir`、`run_command`、`grep`
- **权限闸门**：只读工具自动放行；写文件/执行命令需确认 `y=本次 / a=本会话放行 / n=拒绝`（拒绝原因回喂模型）；危险命令黑名单无条件拒绝
- **沙箱**：文件工具限制在工作目录内，`workspace.extra_roots` 可加白名单
- `run_command`：shell 可配置（`shell: auto/bash/cmd/powershell`），超时 120s，输出保留末尾 50k
- **Ctrl+C** 中断本轮：取消流、杀命令子进程、拒绝待确认操作，已生成部分保留进会话
- `/help` `/new` `/exit`；会话自动落盘到 `~/.jaco/sessions/`，启动自动恢复最近一次
- 单轮最大 LLM 迭代次数 `max_iterations`（默认 25）防失控
- 日志只写 `~/.jaco/logs/`（stdout 属于 TUI）

## 架构（分包即边界，llm/agent 不得依赖 tui）

```
tui     JLine 3 REPL：消费 TurnEvent 渲染、确认交互、Ctrl+C、命令注册表
agent   AgentRunner：驱动工具循环，吐 TurnEvent 事件流（Delta/ToolCall*/ApprovalRequest/Done）
llm     手写 OpenAI 兼容 SSE 客户端：pull 模型流、tool_calls 增量累积、重试、取消
tool    Tool 接口 + ToolRegistry（Schema/Handler 分离）+ 5 个内置工具 + PermissionHook + 路径沙箱
hook    AgentHook 生命周期钩子（权限/审计/续轮等能力的挂载点）
session 会话 JSON 落盘/恢复
config  YAML 配置 + ${ENV} 占位符 + 命名 profile + shell/迭代上限/额外目录
```

## 预留的扩展点（M2+ 使用）

- 新增工具 = 实现 `Tool` 接口 + `registry.register()`，循环零改动
- `AgentHook.onStop` 已接线：返回 true 可强制续轮（"目标闸门"的挂载点）
- 上下文压缩、子 agent（`task` 工具模式）、系统提示词动态分节均已留好入口
