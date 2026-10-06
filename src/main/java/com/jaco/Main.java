package com.jaco;

import com.jaco.agent.AgentRunner;
import com.jaco.config.ConfigLoader;
import com.jaco.config.JacoConfig;
import com.jaco.config.ProviderConfig;
import com.jaco.hook.HookChain;
import com.jaco.llm.OpenAiCompatClient;
import com.jaco.session.SessionStore;
import com.jaco.tui.ConsoleApp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        // jaco.home 必须在任何日志调用前设置（logback.xml 引用它定位日志文件）
        Path home = home();
        System.setProperty("jaco.home", home.toString());

        JacoConfig config = ConfigLoader.load(home);
        if (config == null) {
            printSetupGuide(home);
            return;
        }
        if (config.logLevel() != null) {
            System.setProperty("jaco.log.level", config.logLevel());
        }
        Files.createDirectories(home.resolve("sessions"));
        Files.createDirectories(home.resolve("logs"));

        ProviderConfig provider = config.activeProvider();
        if (provider == null) {
            System.err.println("配置里有多个 provider 但未指定 active，请在 config.yaml 增加: active: <名称>");
            return;
        }
        if (provider.apiKey() == null || provider.apiKey().isBlank()) {
            System.err.println("provider 的 api_key 为空（环境变量未设置或未在配置中填写）");
            return;
        }

        OpenAiCompatClient client = new OpenAiCompatClient(provider.chatCompletionsUrl(), provider.apiKey());
        SessionStore sessions = new SessionStore(home.resolve("sessions"));
        AgentRunner agent = new AgentRunner(
                client, provider, config.systemPrompt(), sessions, new HookChain(List.of()));
        agent.start();

        new ConsoleApp(agent).run();
    }

    /** 数据目录：JACO_HOME 环境变量优先，默认 ~/.jaco。 */
    private static Path home() {
        String env = System.getenv("JACO_HOME");
        return env != null && !env.isBlank()
                ? Path.of(env)
                : Path.of(System.getProperty("user.home"), ".jaco");
    }

    private static void printSetupGuide(Path home) throws IOException {
        String sample = """
                # jaco 配置 — %s
                # ${NAME} 形式的占位符会从环境变量替换，建议 api_key 走环境变量
                active: deepseek
                # system_prompt: 自定义系统提示词（可选）
                # log_level: DEBUG（可选，默认 INFO）
                providers:
                  deepseek:
                    base_url: https://api.deepseek.com
                    api_key: ${DEEPSEEK_API_KEY}
                    model: deepseek-chat
                    temperature: 0.7
                """.formatted(home.resolve("config.yaml"));
        System.out.println("未找到配置文件。请创建以下内容后重新启动：\n");
        System.out.println(sample);
    }
}
