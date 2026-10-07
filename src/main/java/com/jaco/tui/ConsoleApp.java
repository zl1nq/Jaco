package com.jaco.tui;

import com.jaco.agent.AgentRunner;
import com.jaco.agent.TurnAbortedException;
import com.jaco.llm.ChatStream;
import com.jaco.llm.JacoApiException;
import com.jaco.llm.StreamChunk;
import com.jaco.llm.Usage;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 流式 REPL：底部输入、流式滚动输出。
 * 流式期间处于 raw mode，直接往终端写 token；Ctrl+C 中断本次生成，
 * 已收到的部分内容保留进会话。
 */
public final class ConsoleApp {

    private static final String ANSI_RED = "\033[31m";
    private static final String ANSI_GREEN = "\033[32m";
    private static final String ANSI_YELLOW = "\033[33m";
    private static final String ANSI_CYAN = "\033[36m";
    private static final String ANSI_DIM = "\033[90m";
    private static final String ANSI_RESET = "\033[0m";

    private final AgentRunner agent;
    private final Terminal terminal;
    private final LineReader reader;
    private final Map<String, Command> commands = new LinkedHashMap<>();

    public ConsoleApp(AgentRunner agent) throws IOException {
        this.agent = agent;
        this.terminal = TerminalBuilder.builder().system(true).build();
        this.reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .variable(LineReader.HISTORY_FILE, java.nio.file.Path.of(
                        System.getProperty("jaco.home"), "history"))
                .build();
        registerCommands();
    }

    private void registerCommands() {
        commands.put("help", new Command.Simple("help", "显示可用命令", args -> {
            printHelp();
            return true;
        }));
        commands.put("new", new Command.Simple("new", "开新会话", args -> {
            agent.newSession();
            println(ANSI_DIM + "已开始新会话 " + agent.session().id() + ANSI_RESET);
            return true;
        }));
        commands.put("exit", new Command.Simple("exit", "退出（Ctrl+D 同效）", args -> false));
    }

    public void run() {
        println(ANSI_CYAN + "jaco" + ANSI_RESET + ANSI_DIM
                + " · 会话 " + agent.session().id() + " · /help 查看命令" + ANSI_RESET);
        while (true) {
            String line;
            try {
                line = reader.readLine(ANSI_CYAN + "jaco » " + ANSI_RESET);
            } catch (UserInterruptException e) {
                // 输入中的 Ctrl+C：清空当前行，重新开始
                continue;
            } catch (EndOfFileException e) {
                break;
            }
            line = line.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("/")) {
                if (!dispatch(line)) {
                    break;
                }
                continue;
            }
            runTurn(line);
        }
        println(ANSI_DIM + "再见。" + ANSI_RESET);
    }

    private boolean dispatch(String input) {
        String[] parts = input.substring(1).split("\\s+", 2);
        Command command = commands.get(parts[0]);
        if (command == null) {
            println(ANSI_YELLOW + "未知命令 /" + parts[0] + "，/help 查看可用命令" + ANSI_RESET);
            return true;
        }
        return command.execute(parts.length > 1 ? parts[1] : "");
    }

    private void printHelp() {
        println(ANSI_DIM + "命令：" + ANSI_RESET);
        for (Command c : commands.values()) {
            println("  /" + c.name() + ANSI_DIM + "  " + c.description() + ANSI_RESET);
        }
        println(ANSI_DIM + "  Ctrl+C  中断正在生成的回复（已生成部分保留）" + ANSI_RESET);
    }

    // ---- 一轮对话的生命周期 ----

    private void runTurn(String prompt) {
        ChatStream stream;
        try {
            stream = agent.beginTurn(prompt);
        } catch (TurnAbortedException e) {
            println(ANSI_YELLOW + "（本轮已被 hook 拦截）" + ANSI_RESET);
            return;
        } catch (JacoApiException e) {
            println(ANSI_RED + "✗ " + e.getMessage() + ANSI_RESET);
            return;
        } catch (RuntimeException e) {
            println(ANSI_RED + "✗ 请求失败: " + e.getMessage() + ANSI_RESET);
            return;
        }

        long start = System.currentTimeMillis();
        StringBuilder content = new StringBuilder();
        Usage usage = null;
        String finishReason = null;
        Throwable error = null;
        boolean interrupted = false;

        Spinner spinner = new Spinner(terminal);
        var savedAttributes = terminal.getAttributes();
        terminal.enterRawMode();
        try {
            while (true) {
                StreamChunk chunk = stream.poll(60);
                if (chunk == null) {
                    int key = terminal.reader().read(1L);
                    if (key == 3) { // Ctrl+C
                        interrupted = true;
                        stream.cancel();
                        break;
                    }
                    if (content.isEmpty()) {
                        spinner.tick();
                    }
                    continue;
                }
                if (chunk instanceof StreamChunk.Delta d) {
                    if (content.isEmpty()) {
                        spinner.stop();
                    }
                    content.append(d.text());
                    rawPrint(d.text());
                } else if (chunk instanceof StreamChunk.Done d) {
                    finishReason = d.finishReason();
                    usage = d.usage();
                    break;
                } else if (chunk instanceof StreamChunk.Error e) {
                    error = e.cause() != null ? e.cause() : new RuntimeException(e.message());
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            interrupted = true;
            stream.cancel();
        } catch (IOException e) {
            error = e;
        } finally {
            // 内容已开始输出时 spinner 早已停止，再清行会把刚打印的回复擦掉
            if (content.isEmpty()) {
                spinner.stop();
            }
            terminal.writer().flush();
            terminal.setAttributes(savedAttributes);
        }

        if (content.length() > 0) {
            terminal.writer().println();
        }
        agent.endTurn(content.toString(), finishReason, usage, interrupted, error);

        if (interrupted) {
            println(ANSI_YELLOW + "⏹ 已中断，以上部分内容已保留" + ANSI_RESET);
        } else if (error != null) {
            println(ANSI_RED + "✗ 生成中断: " + error.getMessage() + ANSI_RESET);
        }
        println(ANSI_DIM + statusSuffix(usage, start, finishReason) + ANSI_RESET);
    }

    /** raw mode 下 \n 不会回车，统一补 \r。 */
    private void rawPrint(String text) {
        terminal.writer().print(text.replace("\n", "\r\n"));
        terminal.writer().flush();
    }

    private String statusSuffix(Usage usage, long startMillis, String finishReason) {
        StringBuilder sb = new StringBuilder("↳ ");
        if (usage != null) {
            sb.append(usage.promptTokens()).append(" + ").append(usage.completionTokens()).append(" tokens · ");
        }
        sb.append(String.format("%.1fs", (System.currentTimeMillis() - startMillis) / 1000.0));
        if (finishReason != null && !finishReason.equals("stop")) {
            sb.append(" · ").append(finishReason);
        }
        return sb.toString();
    }

    private void println(String text) {
        terminal.writer().println(text);
        terminal.writer().flush();
    }
}
