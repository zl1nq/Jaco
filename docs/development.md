# 架构、扩展与开发

[返回文档目录](README.md) · [项目首页](../README.md)

## 架构与扩展

项目按包划分职责，`llm` 和 `agent` 不依赖 `tui`。

| 包 | 职责 |
| --- | --- |
| `tui` | 基于 JLine 3 的 REPL，消费 `TurnEvent`，处理渲染、确认、命令和中断 |
| `agent` | `AgentRunner` 驱动父工具循环；`AgentLoopSupport` 复用流消费、压缩与归档；`subagent` 包管理预设、子任务生命周期和 edit 审计 |
| `llm` | 手写 OpenAI 兼容 SSE 客户端，处理流式响应、工具调用增量、重试和取消 |
| `tool` | 工具接口与注册表、内置工具、网页抓取、后台任务管理、权限钩子和文件路径沙箱 |
| `render` | Markdown 流式渲染、代码高亮、表格对齐与 CJK 显示宽度计算 |
| `hook` | agent 生命周期钩子，可接入权限、审计与续轮逻辑 |
| `session` | 会话 JSON 保存、恢复与 JSONL 归档 |
| `memory` | 跨会话用户画像、提取策略、待处理队列与按任务选择上下文 |
| `config` | YAML 配置、环境变量替换与模型配置选择 |

主要扩展入口：

- **新增工具**：实现 `Tool` 接口并通过 `ToolRegistry.register()` 注册，无需修改 agent 循环。
- **生命周期钩子**：实现 `AgentHook`；`onStop` 返回 `true` 可以请求续轮。
- **渲染后端**：渲染器输出带语义颜色的 `Span`，终端使用 ANSI 后端，可据此扩展其他界面。

代码高亮支持 Java、Python、JavaScript、Bash 和 JSON，其他语言原样显示；终端颜色自动探测 truecolor，并可降级为 256 色。

## 开发与测试

```bash
mvn test
```

测试使用 JUnit 5，覆盖工具输出归档、上下文压缩、会话存储与切换、归档检索、子任务预设及文件修改权限/审计等行为。`mvn package` 会执行测试并打包可运行 JAR。
