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

Windows 原生终端由 `InteractiveTerminal` 选择 JNI 入口。`org.jline.terminal.impl.jni.win.ShiftEnterWindowsTerminal` 是针对固定 JLine 3.29 的小型桥接类：基类构造器为包可见，故单独放在相同包中，只扩展 Enter 的修饰键映射，输入泵、鼠标、信号和控制台模式恢复仍由 JLine 负责。升级 JLine 时需同时检查该构造器、工厂和原生事件回归；不能仅用预先编码的 CSI 字符串验证 Windows 组合键。`WindowsKeyInputTest` 覆盖 JLine 的原生按键转换，另提供独立控制台烟雾检查入口验证实际 JNI 适配器与编辑器链路。

## 开发与测试

```bash
mvn test
```

测试使用 JUnit 5，覆盖工具输出归档、上下文压缩、会话存储与切换、归档检索、子任务预设及文件修改权限/审计等行为。`mvn package` 会执行测试并打包可运行 JAR。

工具执行返回 `ToolResult`，显式携带成功、失败、拒绝或取消状态和模型可见文本；不能依赖错误文案判断状态。命令超时与非零退出记为失败，状态查询成功不代表被查询任务执行成功。

注册表在工具准备前递归校验基础 Schema 约束，不转换错误类型；Schema 默认拒绝未知字段。互斥参数、文件版本等业务条件继续由工具校验。

工具通过 `isReadOnly()`、`requiresConfirmationChannel()`、`isSubagentAllowed()` 声明属性，默认可写、需要确认渠道且禁止子任务。子任务预设和本次允许列表继续收窄工具范围；只读声明不绕过网页等专用授权策略。

`Tool.description()` 提供简短能力描述，`Tool.prompt()` 维护详细用法。注册表按稳定注册顺序装配，子任务仅接收本次可用工具的说明；通用授权规则保留在 agent 系统提示词中。

`Tool.outputPolicy()` 默认归档并预览，分页工具声明 `PAGINATED`。父子循环共用预算检查，分页结果超限会记为失败，模型回填、完成事件和审计使用同一检查后的结果。

## 会话工作流与计划确认

`Session.workflow` 保存不可变的工作流快照：清单、模式、完整草稿、草稿版本及决定状态。缺少字段的旧会话默认为空清单和普通模式。`Session.withFirstPromptTitle` 共享同一工作流容器，避免首次标题更新覆盖清单；上下文装配每轮重新注入当前状态，不依赖历史工具结果或压缩摘要恢复。

每次主工具批次创建绑定所属会话及轮次的 `WorkflowService`，工具不能读取全局当前会话来确定写入目标。工作流变更在容器锁内持久化，失败恢复原快照。`SessionStore.saveWorkflow` 仅保存已完整配对的工具消息前缀，避免提交计划期间重启恢复出缺失结果的工具批次；不修改运行中消息列表。清单及计划管理声明 `PermissionCategory.SESSION_STATE`，与文件只读和修改操作分别处理，子任务不获得工作流服务。

新增工具默认 `isAllowedInPlan() == false`，必须显式审查后放行；`isPlanOnly()` 限制只在规划时可用的工具。注册表统一过滤定义和说明，执行器在每个调用准备前重新检查，不能依靠模型遵守隐藏定义或用权限 hook 绕过。后台命令通过 `AgentHook.planningBlockers()` 报告尚未清理的资源，子任务由管理器的 active 集合报告，取消请求不等于资源退出。

`PlanApprovalRequest` 独立于 `ApprovalRequest` 和 `QuestionRequest`，携带会话 ID、轮次 ID、草稿版本和失效检查。`submit_plan` 只有在保存批准状态成功后返回成功；拒绝、取消及无渠道保留草稿。拒绝后结束当前轮次，同一批次剩余工具只写拒绝结果以保持消息配对。批准后下一次模型请求重新装配可见工具与说明，不修改权限授权记录。

运行 `mvn verify` 执行全量测试并生成 `target/jaco.jar`。工作流回归覆盖保存失败、过期回答、当前轮次续行、既有授权隔离、后台资源清理、压缩、标题和会话切换；真实终端及模型体验按 [人工验收清单](workflow-acceptance.md) 检查。

## Skill 接入

`com.jaco.skill` 分离技能定义、YAML 解析、目录快照、调用状态和轮次服务。主程序绑定用户与项目目录；嵌入式调用默认空目录。目录发布为不可变快照，单项失败保留诊断，同名项目项优先且不因无效回退。

`SkillService` 在主轮次创建并绑定会话和 TurnHandle，由 ToolContext 跨批次传递。加载先校验开关、源文件版本及预算，再归档调用记录，最后激活；保存失败不发布正文。正文通过请求装配完整注入，工具结果只返回回执。ContextCompactor 为当前技能目录和正文预留历史预算；普通会话和子任务默认没有该预留。历史记录不恢复激活状态。

`skill_read` 使用技能根目录独立构建只读路径边界，复用 ReadFileTool 的结构化 PageResult（正文、版本）和分页逻辑，不能通过解析展示文案判断版本。资源第一次成功读取后固定版本，后续检查真实路径和版本；读取前后检查所属技能仍匹配加载快照。此边界不改变普通文件工具或命令权限。

自动回归覆盖格式与覆盖关系、调用开关、预算、归档失败、正文压缩保留、资源分页及版本、计划限制、取消和会话隔离。示例技能位于 docs/examples/skills，仅供人工复制安装；验收步骤见 [技能文档](skills.md)。
