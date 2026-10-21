<p align="center">
  <img src="jaco.png" alt="JACO" width="640">
</p>

<h1 align="center">JACO</h1>

<p align="center"><em>JAVA CODER</em></p>

<p align="center">用 Java 21 从零构建的终端 AI 编程助手</p>

## 项目简介

JACO 将模型对话、工具调用和权限确认串成完整的 agent 循环。你可以在终端中让它阅读代码、修改文件、执行命令，或委派子 agent 调查问题和修改文件。

项目通过 OpenAI 兼容的 Chat Completions 接口接入模型，支持流式终端交互、会话保存与恢复、上下文压缩和跨会话用户画像。核心 agent 与终端界面独立，便于扩展工具和交互方式。

## 技术栈

| 技术 | 用途 |
| --- | --- |
| Java 21 | 核心运行时与 agent 实现 |
| Maven / Maven Shade Plugin | 依赖管理、测试与包含运行依赖的 JAR 打包 |
| JLine 3 | 终端输入、行编辑与交互界面 |
| Jackson / Jackson YAML | JSON 序列化、会话存储与 YAML 配置解析 |
| JDK HttpClient / SSE | OpenAI 兼容模型接口与流式响应 |
| Apache HttpClient 5 / jsoup | 公共网页抓取与正文提取 |
| SLF4J / Logback | 日志记录 |
| JUnit 5 | 自动化测试 |

## 项目结构

```text
jaco/
├── src/main/java/com/jaco/
│   ├── Main.java           # 应用入口
│   ├── agent/              # agent 循环、上下文压缩与子任务管理
│   ├── config/             # 模型与运行配置
│   ├── hook/               # 生命周期钩子
│   ├── llm/                # 模型客户端、流式响应与工具调用协议
│   ├── memory/             # 跨会话用户画像与提取策略
│   ├── render/             # Markdown 渲染、代码高亮与显示宽度计算
│   ├── session/            # 会话存储与历史归档
│   ├── tool/               # 工具接口、内置工具、权限与路径限制
│   └── tui/                # 终端交互界面
├── src/main/resources/     # 日志配置等资源
├── src/test/               # 自动化测试
├── docs/                   # 使用、配置与开发文档
├── release/                # 预构建 JAR，可能是旧版本
├── pom.xml                 # Maven 构建配置
└── README.md
```

## 运行项目

### 1. 构建并启动

需要 **JDK 21 或更高版本**和 **Maven**，并确保 `java`、`mvn` 已加入 PATH。在项目根目录执行：

```bash
mvn package
java -jar target/jaco.jar
```

`mvn package` 默认执行测试，构建后生成包含运行依赖的 `target/jaco.jar`。

如需跳过测试后运行：

```bash
mvn package -DskipTests
java -jar target/jaco.jar
```

也可以直接运行预构建包（只需 JDK 21 或更高版本）：

```bash
java -jar release/jaco.jar
```

> `release/` 目录下的 JAR 包可能是旧版本，不一定包含当前源码的最新功能和修复。需要使用最新代码时，请自行构建并运行 `target/jaco.jar`。

### 2. 配置模型

首次启动时，如果配置不存在，程序会输出配置示例。请手动创建 `~/.jaco/config.yaml`（Windows 为 `%USERPROFILE%\.jaco\config.yaml`），例如：

```yaml
active: deepseek
providers:
  deepseek:
    base_url: https://api.deepseek.com
    api_key: ${DEEPSEEK_API_KEY}
    model: deepseek-chat
    temperature: 0.7
```

在启动 JACO 的终端中设置对应的 API Key，也可以直接在配置文件的 `api_key` 字段填写。

**PowerShell：**

```powershell
$env:DEEPSEEK_API_KEY = "你的 API Key"
```

**Bash / Zsh：**

```bash
export DEEPSEEK_API_KEY="你的 API Key"
```

### 3. 开始使用

配置完成后重新启动：

```bash
java -jar target/jaco.jar
```

**启动时所在的目录就是工作目录。** 如需操作其他项目，先进入目标目录，再使用 JAR 的绝对路径启动：

```bash
cd /path/to/your-project
java -jar /path/to/jaco/target/jaco.jar
```

进入交互界面后直接输入任务，例如“帮我梳理这个项目的结构，并说明入口和核心模块”。输入 `/help` 查看命令，`/exit` 退出。

## 详细文档

交互命令、工具与权限、子任务、用户画像、完整配置、上下文管理及开发说明，请查看 [文档目录](docs/README.md)。
