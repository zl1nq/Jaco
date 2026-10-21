# 配置与数据存储

[返回文档目录](README.md) · [项目首页](../README.md)

## 首次配置

首次执行以下命令会输出配置示例；请手动创建 `~/.jaco/config.yaml`，然后重新启动：

```bash
java -jar target/jaco.jar
```

Windows 下默认配置路径为 `%USERPROFILE%\.jaco\config.yaml`。设置 `JACO_HOME` 可以更改配置、会话和日志所在的数据目录。

以 DeepSeek 为例：

```yaml
active: deepseek
providers:
  deepseek:
    base_url: https://api.deepseek.com
    api_key: ${DEEPSEEK_API_KEY}
    model: deepseek-chat
    temperature: 0.7
```

可以直接在配置文件中配置 API Key 或者在启动 JACO 的终端中设置 API Key。

**PowerShell：**

```powershell
$env:DEEPSEEK_API_KEY = "你的 API Key"
```

**Bash / Zsh：**

```bash
export DEEPSEEK_API_KEY="你的 API Key"
```

## 模型服务

在 `providers` 中定义命名配置，通过 `active` 选择启动时使用的配置；只有一个配置时可以省略 `active`。

服务需兼容 OpenAI Chat Completions 的流式响应与工具调用协议。`base_url` 支持服务根地址、带 `/v1` 的地址或完整的 `/chat/completions` 地址；未包含 `/chat/completions` 时会自动追加该路径，请按服务实际接口填写。

配置中的 `${VAR}` 会从环境变量替换；未设置的变量会替换为空字符串。当前启动检查要求 `api_key` 非空，使用不需要鉴权的本地服务时也需填写一个非空占位值。未知配置字段会被忽略。

## 可选配置

以下字段与 `active`、`providers` 位于同一级：

```yaml
# system_prompt: 自定义系统提示词
log_level: INFO
shell: auto
max_iterations: 25
context_limit: 65536
max_tool_result_chars: 8000
subagent_timeout_seconds: 300
subagent_max_iterations: 25
workspace:
  extra_roots:
    - D:/other-project
```

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `system_prompt` | 内置提示词 | 自定义系统提示词 |
| `log_level` | `INFO` | 日志级别，可设为 `DEBUG` 排查问题 |
| `shell` | `auto` | 支持 `auto`、`bash`、`cmd`、`powershell` |
| `max_iterations` | `25` | 单轮最大模型调用次数 |
| `context_limit` | `65536` | 上下文压缩的 token 基准，应按模型窗口调整 |
| `max_tool_result_chars` | `8000` | 单条工具输出的归档触发字符数 |
| `subagent_timeout_seconds` | `300` | 子任务整体超时，单位秒，包含上下文压缩 |
| `subagent_max_iterations` | 继承 `max_iterations` | 子任务调查模型调用上限，可单独收紧，不超过父配置；摘要请求不计迭代但计入耗时和已知 token 用量 |
| `workspace.extra_roots` | 空列表 | 文件工具允许访问的额外目录 |

完整字段定义见 [JacoConfig](../src/main/java/com/jaco/config/JacoConfig.java) 和 [ProviderConfig](../src/main/java/com/jaco/config/ProviderConfig.java)。

## 数据存储

默认数据目录为 `~/.jaco/`；设置 `JACO_HOME` 后，下列路径随之迁移：

```text
~/.jaco/
├── config.yaml                  # 模型与运行配置
├── history                      # 终端输入历史
├── user-profile.json            # 用户画像、待处理消息与自动记忆开关
├── sessions/
│   ├── <会话 ID>.json            # 会话记录
│   ├── <会话 ID>.archive.jsonl   # 压缩历史与大工具输出归档
│   └── <会话 ID>-sub-<UUID>.archive.jsonl # 独立子任务归档
└── logs/                        # 运行日志
```

日志写入文件，终端输出留给交互界面。
