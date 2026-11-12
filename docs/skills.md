# 本地技能

[返回文档目录](README.md) · [工具与权限](tools.md)

技能是任务方法和参考资源包，由主任务按需加载。它不会注册新的可执行工具，也不授予文件、命令或计划批准权限。

## 安装与发现

支持两个目录：`<JACO_HOME>/skills/<name>/SKILL.md`（用户技能，JACO_HOME 默认 `~/.jaco`）和 `<项目根目录>/.jaco/skills/<name>/SKILL.md`（项目技能）。仅扫描下一层目录，不扫描 `.claude/skills`。复制 [java-review](examples/skills/java-review/SKILL.md) 或 [test-plan](examples/skills/test-plan/SKILL.md) 时，请复制整个技能目录及其附属资源。

启动时扫描，修改文件后在空闲时执行 `/skills reload`。项目技能优先占据简称，无效项目技能也不会悄悄回退；可用 `user:java-review` 或 `project:java-review` 指定来源。真实路径重复时只保留优先来源，目录中展示错误原因。单项格式错误不会阻止其他技能使用。

## 格式子集

```markdown
---
name: java-review
description: 审查 Java 代码中的行为错误，适用于代码评审。
argument-hint: 文件路径或审查范围
user-invocable: true
disable-model-invocation: false
---

阅读目标代码及相关测试，报告问题位置、触发条件和影响。
```

`name` 和 `description` 必填。名称必须与目录名一致，为 1～64 位小写字母、数字或连字符；简介非空白且不超过 1000 个 UTF-16 字符。`argument-hint` 为可选字符串，两个调用开关分别默认为 true、false。正文非空白、最多 20000 个 UTF-16 字符，文件最多 128 KiB，严格要求 UTF-8。

拒绝重复 YAML 键、错误类型和未知字段。首版不支持 `allowed-tools`、hooks、模型覆盖、fork、环境变量或 Shell 展开。任务说明单独传入模型，不替换正文占位符。脚本可以作为资源阅读，实际运行仍需命令工具及其授权。

## 调用与资源读取

| 入口 | 行为 |
| --- | --- |
| `/skills` | 查看来源、覆盖关系及加载错误，不请求模型 |
| `/skills show <name>` | 展示完整正文，不激活技能 |
| `/skills reload` | 空闲时刷新目录 |
| `/skill <name> [任务说明]` | 校验、加载并归档调用记录后启动轮次，失败不请求模型 |
| `skill_list` | 模型按 query、offset、limit 分页查询可自主加载的技能 |
| `skill_load` | 使用 name 加载本轮说明，重复加载幂等 |
| `skill_read` | 使用 name、包内相对 path 读取已加载技能资源 |

`user-invocable: false` 禁止用户通过 `/skill` 调用；`disable-model-invocation: true` 隐藏模型目录并拒绝自主加载，但仍可由用户显式调用。技能命令仅在空闲时处理；三个工具只供主任务使用，计划模式下允许，但不能解除其执行限制。

`skill_read` 沿用 `read_file` 的 offset、limit、column_offset、expected_version 和 next_line/next_column_offset，受同一输出预算及 64 MiB 文件上限约束。首次读取固定本轮资源版本，后续自动核对；显式提供版本不能覆盖已固定版本。拒绝绝对路径、越界和符号链接逃逸。读取前后核对技能正文版本，正文或资源变化后应在后续轮次重新加载；不提供整个目录的原子快照。用户技能可以位于工作目录之外，这不会扩大普通文件工具的访问范围。

## 生命周期与预算

技能只在当前主任务轮次激活；同一轮次的工具批次和上下文压缩继续使用完整正文。下一轮、切换会话或重启不自动激活；历史调用记录仅作参考。显式 `/skill` 在首个请求前加载，模型 `skill_load` 从下一次请求生效。

目录只注入名称、简介和完整标识，最多 4000 个 UTF-16 字符及配置上下文的 5%；可通过 skill_list 查完整目录。所有激活正文合计最多 40000 个 UTF-16 字符，装配后的正文估算不超过配置上下文的 20%。超限拒绝新增加载，不裁剪说明；压缩为技能预留预算，仍无法容纳当前任务时明确报错。附属资源按需分页，不一次加载整个包。

会话归档记录调用所属会话、轮次、来源、技能完整标识和正文 SHA-256 版本。记录保存失败不激活技能，归档也不被当作恢复执行的指令。

## 人工验收

状态：**待人工验收**。使用本次构建的 `target/jaco.jar`，在独立测试项目复制示例技能后检查：

- [ ] `/skills`、`show`、`reload` 正常，不产生模型请求；无效字段展示明确错误，同名项目技能覆盖用户技能。
- [ ] `/skill java-review 审查当前模块` 在请求前显示加载结果；普通审查任务也能由模型调用 skill_load。
- [ ] 模型可按需读取 references/checklist.md 或 templates/acceptance.md，长资源游标完整，修改资源后拒绝混合版本。
- [ ] 较长任务发生压缩后仍遵守完整技能规则；新一轮、切换或重启不会自动激活旧技能。
- [ ] `/plan` 中加载技能仍不能写文件或运行命令；批准计划后仍遵循原有文件预览、版本检查及独立授权。
- [ ] 用户技能包外路径被拒绝，子任务不能调用技能工具；取消后旧调用失效。
- [ ] `/exit` 和 Ctrl+D 不请求模型，自动记忆待处理队列仍保留。

记录 JAR 构建时间、终端、模型及失败复现步骤；自动测试通过不代表人工验收通过。
