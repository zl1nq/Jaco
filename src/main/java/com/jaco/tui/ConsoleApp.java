package com.jaco.tui;

import com.jaco.agent.AgentRunner;
import com.jaco.agent.TurnEvent;
import com.jaco.agent.TurnHandle;
import com.jaco.llm.Usage;
import com.jaco.tool.UserQuestion;
import com.jaco.tool.ToolException;
import com.jaco.render.MarkdownRenderer;
import com.jaco.render.Ansi;
import com.jaco.render.Span;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;

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
    private String nextInput = "";
    private final Object noticeLock = new Object();
    private boolean readingInput;

    public ConsoleApp(AgentRunner agent) throws IOException {
        this(agent, InteractiveTerminal.open(), null);
    }

    /** 注入终端和行编辑器，以真实事件流验证运行期间的命令输入。 */
    ConsoleApp(AgentRunner agent, Terminal terminal, LineReader lineReader) {
        this.agent = agent;
        this.terminal = terminal;
        this.reader = lineReader != null ? lineReader : new PromptReader(terminal,
                Path.of(System.getProperty("jaco.home"), "history"));
        registerCommands();
    }

    private void registerCommands() {
        commands.put("paste", new Command.Simple("paste", "多行粘贴兼容模式：独立一行 /end 结束，Ctrl+C 取消", args -> {
            if (!args.isBlank()) { println("用法：/paste"); return true; }
            collectPaste();
            return true;
        }));
        commands.put("help", new Command.Simple("help", "显示可用命令", args -> {
            printHelp();
            return true;
        }));
        commands.put("new", new Command.Simple("new", "开新会话（清空上下文与权限放行记录）", args -> {
            if (!flushProfile()) return true;
            agent.newSession();
            println(ANSI_DIM + "已开始新会话 " + agent.session().id() + ANSI_RESET);
            return true;
        }));
        commands.put("sessions", new Command.Simple("sessions", "列出会话（新→旧，* 为当前）", args -> {
            printSessions();
            return true;
        }));
        commands.put("subagents", new Command.Simple("subagents", "子任务状态和结果：/subagents [完整任务ID]；取消：/subagents cancel <ID>", args -> {
            println(agent.subagentCommand(args));
            return true;
        }));
        commands.put("switch", new Command.Simple("switch", "切换会话：/switch <会话id>（支持唯一前缀）", args -> {
            String id = args.strip();
            if (id.isEmpty()) {
                println(ANSI_YELLOW + "用法: /switch <会话id>，/sessions 查看" + ANSI_RESET);
                return true;
            }
            if (agent.profileFlushBeforeSwitch(id) && !flushProfile()) return true;
            String error = agent.switchSession(id);
            if (error != null) {
                println(ANSI_YELLOW + error + ANSI_RESET);
            } else {
                println(ANSI_DIM + "已切换到会话 " + agent.session().id()
                        + "（" + agent.session().messages().size() + " 条消息）" + ANSI_RESET);
            }
            return true;
        }));
        commands.put("delete", new Command.Simple("delete", "删除历史会话：/delete <会话id>（支持唯一前缀，需确认）", args -> {
            println(agent.deleteSession(args, this::confirmSessionDeletion));
            return true;
        }));
        commands.put("memory", new Command.Simple("memory", "用户画像：/memory [forget <ID> | clear | off | on]", args -> {
            println(agent.memoryCommand(args, this::confirmMemoryClear));
            return true;
        }));
        commands.put("exit", new Command.Simple("exit", "退出（Ctrl+D 同效）", args -> false));
        commands.put("cancel", new Command.Simple("cancel", "运行期间中断本轮", args -> {
            println(ANSI_DIM + "当前没有运行中的轮次。" + ANSI_RESET);
            return true;
        }));
    }

    private boolean confirmMemoryClear() {
        println(ANSI_YELLOW + "将清空全部用户画像（包括所有项目），此操作不可恢复。" + ANSI_RESET);
        try {
            String answer = reader.readLine("确认清空？[y/N] ");
            return answer != null && answer.strip().equalsIgnoreCase("y");
        } catch (UserInterruptException | EndOfFileException e) {
            return false;
        }
    }

    private boolean confirmSessionDeletion(com.jaco.session.Session target) {
        println(ANSI_YELLOW + "将删除会话 " + target.id() + "（" + target.displayTitle()
                + "）及对应归档，此操作不可恢复。" + ANSI_RESET);
        try {
            String answer = reader.readLine("确认删除？[y/N] ");
            return answer != null && answer.strip().equalsIgnoreCase("y");
        } catch (UserInterruptException | EndOfFileException e) {
            return false;
        }
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
        Thread notifier = Thread.ofVirtual().name("jaco-subagent-notices").start(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    synchronized (noticeLock) {
                        if (readingInput) {
                            String notice;
                            while ((notice = agent.pollSubagentNotice()) != null) reader.printAbove(notice);
                        }
                    }
                    Thread.sleep(100);
                }
            } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        });
        try { runLoop(); }
        finally {
            synchronized (noticeLock) { readingInput = false; }
            notifier.interrupt();
        }
    }

    private void runLoop() {
        banner();
        while (true) {
            String line;
            try {
                printInputFrameTop();
                String initial = nextInput;
                nextInput = "";
                synchronized (noticeLock) { readingInput = true; }
                try {
                    line = reader.readLine(ANSI_DIM + "│ " + ANSI_RESET
                            + ANSI_GREEN + BOLD + "❯ " + ANSI_RESET, null, initial);
                } finally {
                    synchronized (noticeLock) { readingInput = false; }
                }
                // 管道输入的 dumb 终端不会回显输入或换行，补齐后再画底线。
                if (terminal.getType() != null && terminal.getType().startsWith("dumb")) {
                    println(line);
                }
                printInputFrameBottom();
            } catch (UserInterruptException e) {
                println("");
                printInputFrameBottom();
                continue;
            } catch (EndOfFileException e) {
                println("");
                printInputFrameBottom();
                break;
            }
            line = PromptReader.normalize(line).strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("/") && !line.contains("\n")) {
                if (!dispatch(line)) {
                    break;
                }
                continue;
            }
            runTurn(line);
        }
        flushProfile();
        println(ANSI_DIM + "再见。" + ANSI_RESET);
    }

    private void collectPaste() {
        println(ANSI_DIM + "粘贴多行内容；独立一行输入 /end 结束，返回编辑框后回车发送；Ctrl+C 取消。" + ANSI_RESET);
        StringBuilder pasted = new StringBuilder();
        boolean first = true;
        try {
            while (true) {
                String line = reader.readLine("粘贴 │ ");
                if (line.equals("/end")) break;
                if (!first) pasted.append('\n');
                pasted.append(PromptReader.normalize(line));
                first = false;
            }
            nextInput = pasted.toString();
            println(ANSI_DIM + "内容已放入编辑框，回车发送，Shift+Enter 换行（兼容 Alt+Enter）。" + ANSI_RESET);
        } catch (UserInterruptException | EndOfFileException e) {
            println(ANSI_DIM + "已取消粘贴，内容未发送。" + ANSI_RESET);
        }
    }

    /** 开口边框最多 24 列，保留终端最后一列，降低历史输出在窗口缩小时的折行。 */
    private int inputFrameWidth() {
        int width = terminal.getWidth();
        return Math.max(1, Math.min(24, (width > 0 ? width : 80) - 1));
    }

    private void printInputFrameTop() {
        ensureLineStart();
        println("");
        int width = inputFrameWidth();
        String label = "╭─ 你的输入 ";
        int labelWidth = com.jaco.render.DisplayWidth.width(label);
        if (width >= labelWidth) {
            println(ANSI_DIM + "╭─ " + ANSI_GREEN + BOLD + "你的输入" + ANSI_RESET
                    + ANSI_DIM + " " + "─".repeat(width - labelWidth) + ANSI_RESET);
        } else {
            println(ANSI_DIM + "╭" + "─".repeat(width - 1) + ANSI_RESET);
        }
    }

    private void printInputFrameBottom() {
        println(ANSI_DIM + "╰" + "─".repeat(inputFrameWidth() - 1) + ANSI_RESET);
    }

    /** 无右边框的欢迎横幅——避免中英文混排时的宽度对齐问题。 */
    private void banner() {
        println(ANSI_CYAN + BOLD + "jaco" + ANSI_RESET + ANSI_DIM + " ─────────────────────────────" + ANSI_RESET);
        println(ANSI_DIM + "│ model  " + ANSI_RESET + agent.modelName());
        println(ANSI_DIM + "│ 会话   " + ANSI_RESET + agent.session().id());
        if (terminal instanceof org.jline.terminal.impl.jni.win.ShiftEnterWindowsTerminal)
            println(ANSI_DIM + "│ 输入   Windows 原生按键 · Shift+Enter 换行" + ANSI_RESET);
        else if (terminal instanceof org.jline.terminal.impl.AbstractWindowsTerminal<?>)
            println(ANSI_DIM + "│ 输入   Windows 默认终端，原生组合键适配未启用，可使用 Alt+Enter" + ANSI_RESET);
        else if (terminal.getType() != null && terminal.getType().startsWith("dumb"))
            println(ANSI_DIM + "│ 输入   普通流式控制台；组合键可能无法区分，建议在 Terminal 中运行" + ANSI_RESET);
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
        println(ANSI_DIM + "  Enter 发送 · Shift+Enter 换行（兼容 Alt+Enter）· 支持整段粘贴；不支持的终端可先输入 /paste" + ANSI_RESET);
        println(ANSI_DIM + "  运行期间可输入 /subagents [任务ID]、/help、/cancel，回车执行；Ctrl+U 清空输入" + ANSI_RESET);
    }

    // ---- 一轮对话：消费 agent loop 的事件流 ----

    private void runTurn(String prompt) {
        consumeTurn(agent.runTurn(prompt));
    }

    private boolean flushProfile() {
        return !agent.hasPendingUserProfile() || consumeTurn(agent.flushUserProfile());
    }

    boolean consumeTurn(TurnHandle handle) {
        long start = System.currentTimeMillis();
        Spinner spinner = new Spinner(terminal);
        MarkdownRenderer renderer = new MarkdownRenderer();
        StringBuilder pending = new StringBuilder();
        var savedAttributes = terminal.getAttributes();
        terminal.enterRawMode();
        rawPasteMode(true);

        Usage usage = null;
        String finishReason = null;
        boolean interrupted = false;
        boolean aborted = false;
        Throwable error = null;
        int iterations = 0;
        boolean sawOutput = false;
        boolean replyLabeled = false;
        RunningCommandInput input = new RunningCommandInput();
        boolean inputVisible = false;
        var deferredRequests = new java.util.ArrayDeque<TurnEvent>();

        try {
            while (true) {
                // 每次迭代都读按键，持续有输出时也能及时响应查询与取消。
                int key = terminal.reader().read(1L);
                var action = input.accept(key);
                if (action != RunningCommandInput.Action.NONE) {
                    if (inputVisible) {
                        clearRunningInput();
                        inputVisible = false;
                    }
                    if (!sawOutput) spinner.stop();
                    if (action == RunningCommandInput.Action.CANCEL) {
                        handle.cancel();
                    } else if (action == RunningCommandInput.Action.SUBMIT) {
                        flushPending(renderer, pending);
                        ensureLineStart();
                        println(ANSI_GREEN + "运行命令 ❯ " + input.text() + ANSI_RESET);
                        dispatchRunningCommand(input.text(), handle);
                        input.clear();
                    } else if (pending.isEmpty()) {
                        ensureLineStart();
                    }
                }
                if (handle.isCancelled()) deferredRequests.clear();
                TurnEvent event;
                String notice = agent.pollSubagentNotice();
                if (notice != null) {
                    event = new TurnEvent.Notice(notice);
                } else if (!deferredRequests.isEmpty() && input.isEmpty()) {
                    event = deferredRequests.removeFirst();
                } else {
                    event = handle.poll(60);
                }
                if (event == null) {
                    if (!input.isEmpty() && !inputVisible && atLineStart && pending.isEmpty()) {
                        drawRunningInput(input.text());
                        inputVisible = true;
                    } else if (!sawOutput && input.isEmpty()) {
                        spinner.tick();
                    }
                    continue;
                }
                if (inputVisible) {
                    clearRunningInput();
                    inputVisible = false;
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
                    println(ANSI_YELLOW + "⏺ " + origin(s.taskId()) + s.tool() + ANSI_RESET + ANSI_DIM + "(" + s.summary() + ")" + ANSI_RESET);
                } else if (event instanceof TurnEvent.ToolPreview preview) {
                    flushPending(renderer, pending);
                    if (preview.taskId() != null) println(ANSI_DIM + origin(preview.taskId()) + "修改预览" + ANSI_RESET);
                    for (String line : preview.text().split("\n")) {
                        String color = line.startsWith("+") ? ANSI_GREEN
                                : line.startsWith("-") ? ANSI_RED : ANSI_DIM;
                        println(color + line + ANSI_RESET);
                    }
                } else if (event instanceof TurnEvent.ToolCallEnd e) {
                    // 结果预览挂一条竖轨，视觉上归属上方的 ⏺
                    String[] lines = e.summary().split("\\n");
                    String rail = e.ok() ? ANSI_DIM + "  ╰─ " : ANSI_RED + "  ✗ ";
                    println(rail + (e.ok() ? ANSI_DIM : "") + origin(e.taskId()) + lines[0] + ANSI_RESET);
                    for (int i = 1; i < lines.length; i++) {
                        println(ANSI_DIM + "  │ " + lines[i] + ANSI_RESET);
                    }
                } else if (event instanceof TurnEvent.Notice n) {
                    flushPending(renderer, pending);
                    // 暗色状态行；spinner 在下一行继续（sawOutput 仍为 false 时）
                    spinner.stop();
                    rawPrint(ANSI_DIM + n.text() + ANSI_RESET + "\n");
                    if (n.text().startsWith("⟣ 子任务开始")) {
                        rawPrint(ANSI_DIM + "输入 /subagents 查询状态 · /cancel 或 Ctrl+C 中断" + ANSI_RESET + "\n");
                    }
                } else if (event instanceof TurnEvent.ApprovalRequest a) {
                    // 用户正在输入时延后确认，不能让剩余按键被当作授权答案。
                    if (!input.isEmpty()) {
                        deferredRequests.addLast(a);
                    } else {
                        terminal.writer().flush();
                        terminal.setAttributes(savedAttributes);
                        String answer = askApproval(a);
                        a.resolver().accept(answer);
                        terminal.enterRawMode();
                        rawPasteMode(true);
                    }
                } else if (event instanceof TurnEvent.QuestionRequest question) {
                    if (question.active().getAsBoolean()) {
                        if (!input.isEmpty()) {
                            deferredRequests.addLast(question);
                            println(ANSI_DIM + "（有问题待回答，请先提交当前运行命令，或按 Ctrl+U 清空输入）" + ANSI_RESET);
                        } else {
                            spinner.stop();
                            sawOutput = true;
                            flushPending(renderer, pending);
                            askQuestion(question, handle);
                        }
                    }
                } else if (event instanceof TurnEvent.Done d) {
                    usage = d.usage();
                    finishReason = d.finishReason();
                    interrupted = d.interrupted();
                    aborted = d.abortedByHook();
                    error = d.error();
                    iterations = d.iterations();
                    break;
                }
                if (!input.isEmpty() && atLineStart && pending.isEmpty()) {
                    drawRunningInput(input.text());
                    inputVisible = true;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handle.cancel();
            interrupted = true;
        } catch (IOException e) {
            handle.cancel();
            error = e;
        } finally {
            if (inputVisible) clearRunningInput();
            if (!sawOutput) {
                spinner.stop();
            }
            flushPending(renderer, pending);
            if (!input.isEmpty()) {
                ensureLineStart();
                nextInput = input.text();
                println(ANSI_DIM + "（本轮已结束，未提交的输入保留到输入框）" + ANSI_RESET);
            }
            terminal.writer().flush();
            rawPasteMode(false);
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
        return !interrupted && error == null;
    }

    private void clearRunningInput() {
        terminal.writer().print("\r\033[2K");
        terminal.writer().flush();
        atLineStart = true;
    }

    private void drawRunningInput(String text) {
        // 只显示一行尾部，避免窄终端换行后清除到模型输出。
        drawInput(text, "运行命令 ❯ ");
    }

    private void drawInput(String text, String label) {
        drawInput(text, label, "");
    }

    private void drawInput(String text, String label, String error) {
        text = text.replace("\n", " ↵ ").replace("\t", "    ");
        int width = terminal.getWidth() > 0 ? terminal.getWidth() : 80;
        String prefix = width >= 16 ? label : width >= 4 ? "> " : "";
        String status = "";
        int statusBudget = Math.max(0, width - com.jaco.render.DisplayWidth.width(prefix) - 12);
        if (!error.isEmpty() && statusBudget >= 4) {
            int end = 0, columns = 0;
            while (end < error.length()) {
                int next = error.offsetByCodePoints(end, 1);
                int size = com.jaco.render.DisplayWidth.width(error.substring(end, next));
                if (columns + size > statusBudget - 4) break;
                columns += size;
                end = next;
            }
            status = "[" + error.substring(0, end) + (end < error.length() ? "…" : "") + "] ";
        }
        int available = Math.max(1, width - com.jaco.render.DisplayWidth.width(prefix + status) - 1);
        int start = text.length(), used = 0;
        while (start > 0) {
            int previous = text.offsetByCodePoints(start, -1);
            int columns = com.jaco.render.DisplayWidth.width(text.substring(previous, start));
            if (used + columns > available) break;
            used += columns;
            start = previous;
        }
        String visible = text.substring(start);
        rawPrint((status.isEmpty() ? "" : ANSI_YELLOW + status) + ANSI_GREEN + prefix + visible + ANSI_RESET);
    }

    /** 保持 raw mode，用短轮询响应外部取消，不把未作答当成采用推荐项。 */
    private void askQuestion(TurnEvent.QuestionRequest request, TurnHandle handle) throws IOException {
        UserQuestion question = request.question();
        ensureLineStart();
        println(ANSI_YELLOW + "╭─ " + (question.options().isEmpty() ? "需要补充信息" : "需要你的选择")
                + (question.header().isBlank() ? "" : " · " + question.header()) + ANSI_RESET);
        for (String line : question.question().split("\n")) println("│ " + line);
        for (int i = 0; i < question.options().size(); i++) {
            var option = question.options().get(i);
            println("│ " + (i + 1) + ". " + option.label() + (option.recommended() ? "（推荐）" : ""));
            if (!option.description().isBlank()) println("│    " + option.description().replace("\n", " "));
        }
        if (question.recommended() != null) println("│ 推荐理由：" + question.recommendationReason().replace("\n", " "));
        println("╰─ " + (question.recommended() == null ? "输入答案；/skip 跳过" : "输入编号或自定义答案；回车或 /skip 采用 " + question.recommended().label())
                + "；/text 输入数字文本；Shift+Enter 换行（兼容 Alt+Enter），粘贴后回车提交；Ctrl+C 取消");
        RunningCommandInput answer = new RunningCommandInput(UserQuestion.MAX_ANSWER_LENGTH, true);
        String error = "";
        String overflowMessage = "输入达到上限，部分内容未录入；请退格修改或 Ctrl+U 清空后重新提交";
        drawInput("", "回答 ❯ ");
        while (request.active().getAsBoolean() && !handle.isCancelled()) {
            int key = terminal.reader().read(50L);
            if (key == 4 || key == -1) { handle.cancel(); break; }
            var action = answer.accept(key);
            if (action == RunningCommandInput.Action.CANCEL) { handle.cancel(); break; }
            if (action == RunningCommandInput.Action.SUBMIT) {
                try {
                    if (answer.overflowed()) throw new ToolException(overflowMessage);
                    var submission = question.parseInput(request.requestId(), answer.text());
                    var resolved = question.answer(submission);
                    if (!request.active().getAsBoolean() || handle.isCancelled()) {
                        clearRunningInput();
                        return;
                    }
                    request.resolver().accept(submission);
                    clearRunningInput();
                    if (resolved.source().equals("recommended_default")) println(ANSI_DIM + "已采用推荐项：" + question.recommended().label() + ANSI_RESET);
                    else if (resolved.source().equals("user_selection")) println("已选择：" + question.options().stream()
                            .filter(o -> o.id().equals(resolved.selectedOptionIds().getFirst())).findFirst().orElseThrow().label());
                    else println(resolved.status().equals("skipped") ? "已跳过，未提供答案" : "已回答：" + resolved.customText());
                    return;
                } catch (ToolException e) {
                    if (!e.getMessage().equals(error)) {
                        error = e.getMessage();
                        clearRunningInput();
                        drawInput(answer.text(), "回答 ❯ ", error);
                    }
                }
            } else if (action == RunningCommandInput.Action.OVERFLOW) {
                clearRunningInput();
                error = overflowMessage;
                drawInput(answer.text(), "回答 ❯ ", error);
            } else if (action == RunningCommandInput.Action.EDITED) {
                clearRunningInput();
                error = answer.overflowed() ? overflowMessage : "";
                drawInput(answer.text(), "回答 ❯ ", error);
            }
        }
        clearRunningInput();
    }

    void dispatchRunningCommand(String input, TurnHandle handle) {
        String line = input.strip();
        if (line.contains("\n")) {
            println(ANSI_YELLOW + "运行期间只支持单行命令，请等本轮结束后发送多行任务。" + ANSI_RESET);
            return;
        }
        String command = line.split("\\s+", 2)[0];
        if (command.equals("/subagents") || command.equals("/help")) {
            dispatch(line);
        } else if (line.equals("/cancel")) {
            handle.cancel();
        } else {
            println(ANSI_YELLOW + "运行期间支持 /subagents [任务ID]、/help、/cancel；其他命令请在本轮结束后执行。" + ANSI_RESET);
        }
    }

    private void rawPasteMode(boolean enabled) {
        if (terminal.getType() != null && !terminal.getType().startsWith("dumb")) {
            terminal.writer().print(enabled ? "\033[?2004h" : "\033[?2004l");
            terminal.writer().flush();
        }
    }

    private String askApproval(TurnEvent.ApprovalRequest request) {
        println(ANSI_YELLOW + "╭─ 需要确认 ─────────────────" + ANSI_RESET);
        if (request.taskId() != null) println(ANSI_YELLOW + "│ " + ANSI_RESET + origin(request.taskId()));
        println(ANSI_YELLOW + "│ " + ANSI_RESET + request.detail());
        String approval = request.tool().equals("web_fetch") ? " y=本次 · a=本会话同源站点放行 · n=拒绝"
                : " y=本次 · a=本会话放行 · n=拒绝";
        println(ANSI_YELLOW + "╰─" + ANSI_RESET + ANSI_DIM + approval + ANSI_RESET);
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

    private static String origin(String taskId) {
        return taskId == null ? "" : "[子任务 " + taskId + "] ";
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
