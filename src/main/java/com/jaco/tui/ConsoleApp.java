package com.jaco.tui;

import com.jaco.agent.AgentRunner;
import com.jaco.agent.TurnEvent;
import com.jaco.agent.TurnHandle;
import com.jaco.llm.Usage;
import com.jaco.render.MarkdownRenderer;
import com.jaco.render.Ansi;
import com.jaco.render.Span;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 流式 REPL：底部输入、流式滚动输出。消费 AgentRunner 的 TurnEvent。
 * 流式期间处于 raw mode；Ctrl+C 中断本轮（取消流 / 杀命令子进程 / 拒绝待确认操作），
 * 已收到的部分内容保留进会话。
 */
public final class ConsoleApp {

    private static final String ANSI_RED = "\033[31m";
    private static final String ANSI_GREEN = "\033[32m";
    private static final String ANSI_YELLOW = "\033[33m";
    private static final String ANSI_CYAN = "\033[36m";
    private static final String ANSI_DIM = "\033[90m";
    private static final String BOLD = "\033[1m";
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
                .variable(LineReader.HISTORY_FILE, Path.of(
                        System.getProperty("jaco.home"), "history"))
                .build();
        registerCommands();
    }

    private void registerCommands() {
        commands.put("help", new Command.Simple("help", "显示可用命令", args -> {
            printHelp();
            return true;
        }));
        commands.put("new", new Command.Simple("new", "开新会话（清空上下文与权限放行记录）", args -> {
            agent.newSession();
            println(ANSI_DIM + "已开始新会话 " + agent.session().id() + ANSI_RESET);
            return true;
        }));
        commands.put("sessions", new Command.Simple("sessions", "列出会话（新→旧，* 为当前）", args -> {
            printSessions();
            return true;
        }));
        commands.put("switch", new Command.Simple("switch", "切换会话：/switch <会话id>（支持唯一前缀）", args -> {
            String id = args.strip();
            if (id.isEmpty()) {
                println(ANSI_YELLOW + "用法: /switch <会话id>，/sessions 查看" + ANSI_RESET);
                return true;
            }
            String error = agent.switchSession(id);
            if (error != null) {
                println(ANSI_YELLOW + error + ANSI_RESET);
            } else {
                println(ANSI_DIM + "已切换到会话 " + agent.session().id()
                        + "（" + agent.session().messages().size() + " 条消息）" + ANSI_RESET);
            }
            return true;
        }));
        commands.put("exit", new Command.Simple("exit", "退出（Ctrl+D 同效）", args -> false));
    }

    private void printSessions() {
        List<com.jaco.session.Session> sessions = agent.listSessions();
        if (sessions.isEmpty()) {
            println(ANSI_DIM + "（暂无历史会话）" + ANSI_RESET);
            return;
        }
        String currentId = agent.session().id();
        for (com.jaco.session.Session s : sessions) {
            String marker = s.id().equals(currentId) ? ANSI_GREEN + "*" + ANSI_RESET : " ";
            println(marker + " " + s.id() + "  " + s.displayTitle() + ANSI_DIM + "  "
                    + s.messages().size() + " 条消息" + ANSI_RESET);
        }
    }

    public void run() {
        banner();
        while (true) {
            String line;
            try {
                line = reader.readLine(ANSI_CYAN + "你 » " + ANSI_RESET);
            } catch (UserInterruptException e) {
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

    /** 无右边框的欢迎横幅——避免中英文混排时的宽度对齐问题。 */
    private void banner() {
        println(ANSI_CYAN + BOLD + "jaco" + ANSI_RESET + ANSI_DIM + " ─────────────────────────────" + ANSI_RESET);
        println(ANSI_DIM + "│ model  " + ANSI_RESET + agent.modelName());
        println(ANSI_DIM + "│ 会话   " + ANSI_RESET + agent.session().id());
        println(ANSI_DIM + "│ /help 命令 · Ctrl+C 中断本轮" + ANSI_RESET);
        println(ANSI_DIM + "╰──────────────────────────────" + ANSI_RESET);
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
        println(ANSI_DIM + "  Ctrl+C  中断本轮（取消流/杀命令/拒绝确认，已生成部分保留）" + ANSI_RESET);
    }

    // ---- 一轮对话：消费 agent loop 的事件流 ----

    private void runTurn(String prompt) {
        long start = System.currentTimeMillis();
        TurnHandle handle = agent.runTurn(prompt);
        Spinner spinner = new Spinner(terminal);
        MarkdownRenderer renderer = new MarkdownRenderer();
        StringBuilder pending = new StringBuilder();
        var savedAttributes = terminal.getAttributes();
        terminal.enterRawMode();

        Usage usage = null;
        String finishReason = null;
        boolean interrupted = false;
        boolean aborted = false;
        Throwable error = null;
        int iterations = 0;
        boolean sawOutput = false;
        boolean replyLabeled = false;

        try {
            while (true) {
                TurnEvent event = handle.poll(60);
                if (event == null) {
                    int key = terminal.reader().read(1L);
                    if (key == 3) { // Ctrl+C
                        handle.cancel();
                    }
                    if (!sawOutput) {
                        spinner.tick();
                    }
                    continue;
                }
                if (event instanceof TurnEvent.Delta d) {
                    if (!sawOutput) {
                        spinner.stop();
                        sawOutput = true;
                    }
                    // jaco 标签贴在第一段正文前（模型先调工具时自动落到正文处）
                    if (!replyLabeled) {
                        rawPrint(ANSI_CYAN + BOLD + "jaco" + ANSI_RESET + ANSI_DIM + " » " + ANSI_RESET);
                        replyLabeled = true;
                    }
                    // markdown 渲染只处理完整行；不完整的尾行留在缓冲区；
                    // 返回 null 表示该行进入了表格缓冲
                    pending.append(d.text());
                    int nl;
                    while ((nl = pending.indexOf("\n")) >= 0) {
                        String text = pending.substring(0, nl);
                        pending.delete(0, nl + 1);
                        printRendered(renderer.renderLine(text));
                    }
                } else if (event instanceof TurnEvent.ToolCallStart s) {
                    if (!sawOutput) {
                        spinner.stop();
                        sawOutput = true;
                    }
                    flushPending(renderer, pending);
                    println(ANSI_YELLOW + "⏺ " + s.tool() + ANSI_RESET + ANSI_DIM + "(" + s.summary() + ")" + ANSI_RESET);
                } else if (event instanceof TurnEvent.ToolCallEnd e) {
                    // 结果预览挂一条竖轨，视觉上归属上方的 ⏺
                    String[] lines = e.summary().split("\\n");
                    String rail = e.ok() ? ANSI_DIM + "  ╰─ " : ANSI_RED + "  ✗ ";
                    println(rail + (e.ok() ? ANSI_DIM : "") + lines[0] + ANSI_RESET);
                    for (int i = 1; i < lines.length; i++) {
                        println(ANSI_DIM + "  │ " + lines[i] + ANSI_RESET);
                    }
                } else if (event instanceof TurnEvent.Notice n) {
                    // 暗色状态行；spinner 在下一行继续（sawOutput 仍为 false 时）
                    spinner.stop();
                    rawPrint(ANSI_DIM + n.text() + ANSI_RESET + "\n");
                } else if (event instanceof TurnEvent.ApprovalRequest a) {
                    // 退出 raw mode 才能用 LineReader 正常读入
                    terminal.writer().flush();
                    terminal.setAttributes(savedAttributes);
                    String answer = askApproval(a);
                    a.resolver().accept(answer);
                    terminal.enterRawMode();
                } else if (event instanceof TurnEvent.Done d) {
                    usage = d.usage();
                    finishReason = d.finishReason();
                    interrupted = d.interrupted();
                    aborted = d.abortedByHook();
                    error = d.error();
                    iterations = d.iterations();
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handle.cancel();
            interrupted = true;
        } catch (IOException e) {
            error = e;
        } finally {
            if (!sawOutput) {
                spinner.stop();
            }
            flushPending(renderer, pending);
            terminal.writer().flush();
            terminal.setAttributes(savedAttributes);
        }
        ensureLineStart();

        turnMillis = System.currentTimeMillis() - start;
        if (aborted) {
            println(ANSI_YELLOW + "（本轮已被 hook 拦截）" + ANSI_RESET);
        } else if (interrupted) {
            println(ANSI_YELLOW + "⏹ 已中断，以上部分内容已保留" + ANSI_RESET);
        } else if (error != null) {
            println(ANSI_RED + "✗ 出错: " + error.getMessage() + ANSI_RESET);
        }
        String mark = !aborted && !interrupted && error == null ? ANSI_GREEN + "✓" : ANSI_DIM + "·";
        println(mark + ANSI_RESET + ANSI_DIM + statusSuffix(usage, finishReason, iterations) + ANSI_RESET);
    }

    private String askApproval(TurnEvent.ApprovalRequest request) {
        println(ANSI_YELLOW + "╭─ 需要确认 ─────────────────" + ANSI_RESET);
        println(ANSI_YELLOW + "│ " + ANSI_RESET + request.detail());
        println(ANSI_YELLOW + "╰─" + ANSI_RESET + ANSI_DIM + " y=本次 · a=本会话放行 · n=拒绝" + ANSI_RESET);
        while (true) {
            String line;
            try {
                line = reader.readLine("允许? ");
            } catch (UserInterruptException e) {
                return "n";
            } catch (EndOfFileException e) {
                return "n";
            }
            line = line.strip().toLowerCase();
            if (line.equals("y") || line.equals("a") || line.equals("n")) {
                return line;
            }
        }
    }

    /** 把不完整的尾行按完整行渲染输出，并冲出渲染器的表格/挂起缓冲（Done/中断/工具开始前调用）。 */
    private void flushPending(MarkdownRenderer renderer, StringBuilder pending) {
        boolean printed = false;
        if (pending.length() > 0) {
            String rest = pending.toString();
            pending.setLength(0);
            printed = printRendered(renderer.renderLine(rest)) | printed;
        }
        List<List<Span>> buffered = renderer.flush();
        if (buffered != null) {
            for (List<Span> l : buffered) {
                rawPrint(Ansi.render(l) + "\n");
            }
            printed = true;
        }
        if (!printed && !atLineStart) {
            // 渲染器无输出但光标悬在行中时保证换行
            rawPrint("\n");
        }
    }

    /** 渲染结果可能为 null（表格缓冲）；返回是否有实际输出。 */
    private boolean printRendered(List<List<Span>> lines) {
        if (lines == null) {
            return false;
        }
        for (List<Span> l : lines) {
            rawPrint(Ansi.render(l) + "\n");
        }
        return true;
    }

    /** raw mode 下 \n 不会回车，统一补 \r。 */
    private void rawPrint(String text) {
        terminal.writer().print(text.replace("\n", "\r\n"));
        terminal.writer().flush();
        atLineStart = text.endsWith("\n");
    }

    private boolean atLineStart = true;

    private void ensureLineStart() {
        if (!atLineStart) {
            terminal.writer().print("\r\n");
            terminal.writer().flush();
            atLineStart = true;
        }
    }

    private String statusSuffix(Usage usage, String finishReason, int iterations) {
        StringBuilder sb = new StringBuilder("↳ ");
        if (usage != null) {
            sb.append(usage.promptTokens()).append(" + ").append(usage.completionTokens()).append(" tokens · ");
        }
        if (iterations > 1) {
            sb.append(iterations).append(" 轮 · ");
        }
        long millis = turnMillis;
        sb.append(String.format("%.1fs", millis / 1000.0));
        if (finishReason != null && !finishReason.equals("stop")) {
            sb.append(" · ").append(finishReason);
        }
        return sb.toString();
    }

    private long turnMillis;

    private void println(String text) {
        atLineStart = true;
        terminal.writer().println(text);
        terminal.writer().flush();
    }
}
