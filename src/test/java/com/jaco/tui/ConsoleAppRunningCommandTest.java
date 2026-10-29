package com.jaco.tui;

import com.jaco.agent.*;
import com.jaco.config.ProviderConfig;
import com.jaco.hook.HookChain;
import com.jaco.llm.*;
import com.jaco.session.SessionStore;
import com.jaco.tool.ToolRegistry;
import com.jaco.tool.ToolSandbox;
import com.jaco.tool.builtin.TaskTool;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.NonBlockingReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.*;

class ConsoleAppRunningCommandTest {
    @TempDir Path tmp;

    private AgentRunner agent(OpenAiCompatClient client, ToolRegistry registry) throws Exception {
        var agent = new AgentRunner(client, new ProviderConfig("http://localhost", "k", "m", null), null,
                new SessionStore(tmp), new HookChain(List.of()), registry, new ToolSandbox(tmp, List.of()),
                tmp, "auto", 10, 100_000, 8000);
        agent.start();
        return agent;
    }

    /** 合成按键和终端输出，事件仍通过真实 TurnHandle 队列消费。 */
    private static final class Console {
        final StringWriter output = new StringWriter();
        final ArrayDeque<Integer> keys = new ArrayDeque<>();
        IntConsumer onRead = count -> { };
        Runnable onIdle = () -> { };
        Function<Object[], String> readLine = args -> { throw new EndOfFileException(); };
        java.util.function.Consumer<String> printAbove = text -> { };
        int reads;
        final NonBlockingReader input = new NonBlockingReader() {
            @Override protected int read(long timeout, boolean peek) {
                if (keys.isEmpty()) onIdle.run();
                if (keys.isEmpty()) return READ_EXPIRED;
                int key = peek ? keys.peek() : keys.remove();
                if (!peek) onRead.accept(++reads);
                return key;
            }
            @Override public int readBuffered(char[] target, int offset, int length, long timeout) {
                if (length == 0) return 0;
                int key = read(timeout, false);
                if (key < 0) return key;
                target[offset] = (char) key;
                return 1;
            }
            @Override public void close() { }
        };
        final Terminal terminal = (Terminal) Proxy.newProxyInstance(Terminal.class.getClassLoader(),
                new Class<?>[]{Terminal.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "reader" -> input;
                    case "writer" -> new PrintWriter(output);
                    case "getAttributes", "enterRawMode" -> new Attributes();
                    case "getWidth" -> 80;
                    case "getType" -> "xterm";
                    default -> null;
                });
        final LineReader lineReader = (LineReader) Proxy.newProxyInstance(LineReader.class.getClassLoader(),
                new Class<?>[]{LineReader.class}, (proxy, method, args) -> {
                    if (method.getName().equals("readLine")) return readLine.apply(args);
                    if (method.getName().equals("printAbove")) printAbove.accept((String) args[0]);
                    return null;
                });

        void type(String text) { text.codePoints().forEach(keys::add); }
        ConsoleApp app(AgentRunner agent) { return new ConsoleApp(agent, terminal, lineReader); }
        String text() { return output.toString(); }
    }

    @Test void idleSubagentsCommandIsRegisteredAndDoesNotCallModel() throws Exception {
        try (var agent = agent(null, new ToolRegistry())) {
            Console console = new Console();
            AtomicInteger lines = new AtomicInteger();
            console.readLine = args -> lines.getAndIncrement() == 0 ? "/subagents" : "/exit";
            console.app(agent).run();
            assertTrue(console.text().contains("当前会话暂无子任务"));
            assertFalse(console.text().contains("未知命令"));
        }
    }

    @Test void backgroundCompletionWhileReadingUsesPrintAboveWithoutRestartingInput() throws Exception {
        var stream = TestStreams.of();
        var client = new OpenAiCompatClient("http://localhost", "k") {
            @Override public ChatStream chatStream(ChatRequest request) { return stream; }
        };
        try (var agent = agent(client, new ToolRegistry())) {
            agent.runSubagent(new com.jaco.agent.subagent.SubagentRequest("调查",
                    com.jaco.agent.subagent.SubagentProfile.EXPLORE, null, true), () -> false);
            var console = new Console();
            var notice = new AtomicReference<String>();
            var notified = new CountDownLatch(1);
            var reads = new AtomicInteger();
            console.printAbove = text -> { notice.set(text); notified.countDown(); };
            console.readLine = args -> {
                reads.incrementAndGet();
                TestStreams.emit(stream, new StreamChunk.Delta("后台报告"));
                TestStreams.emit(stream, new StreamChunk.Done("stop", null));
                try { assertTrue(notified.await(3, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new AssertionError(e); }
                return "/exit";
            };
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> console.app(agent).run());
            assertEquals(1, reads.get());
            assertTrue(notice.get().contains("后台子任务结束"));
            assertTrue(notice.get().contains("SUCCEEDED"));
            assertNull(agent.pollSubagentNotice());
        }
    }

    @Test void backgroundCompletionDuringTurnIsRenderedAndKeepsUnsubmittedCommand() throws Exception {
        var stream = TestStreams.of();
        var client = new OpenAiCompatClient("http://localhost", "k") {
            @Override public ChatStream chatStream(ChatRequest request) { return stream; }
        };
        try (var agent = agent(client, new ToolRegistry()); var turn = new TurnHandle()) {
            agent.runSubagent(new com.jaco.agent.subagent.SubagentRequest("调查",
                    com.jaco.agent.subagent.SubagentProfile.EXPLORE, null, true), () -> false);
            Console console = new Console();
            console.type("/");
            console.onRead = n -> {
                TestStreams.emit(stream, new StreamChunk.Done("stop", null));
            };
            var app = console.app(agent);
            Thread finisher = Thread.ofVirtual().start(() -> {
                try {
                    while (!console.text().contains("后台子任务结束")) Thread.sleep(10);
                    TestTurnEvents.emit(turn, new TurnEvent.Done(null, "stop", false, false, null, 1));
                } catch (InterruptedException ignored) { }
            });
            try {
                assertTimeoutPreemptively(Duration.ofSeconds(5), () -> app.consumeTurn(turn));
                assertTrue(console.text().contains("SUCCEEDED"));
                console.readLine = args -> {
                    assertEquals("/", args[2]);
                    return "/exit";
                };
                app.run();
            } finally { finisher.interrupt(); }
        }
    }

    @Test void queryDuringLiveSubagentKeepsTaskRunningAndCancelStopsParent() throws Exception {
        CountDownLatch childStarted = new CountDownLatch(1);
        AtomicReference<Thread> parentThread = new AtomicReference<>();
        var client = new OpenAiCompatClient("http://localhost", "k") {
            @Override public ChatStream chatStream(ChatRequest request) {
                // 测试注册表只有 task，子任务没有可用工具时请求省略 tools 字段。
                if (request.tools() != null && request.tools().stream().anyMatch(t -> t.function().name().equals("task"))) {
                    parentThread.set(Thread.currentThread());
                    return TestStreams.of(new StreamChunk.ToolCallDelta(0, "t1", "task", "{\"prompt\":\"调查\"}"),
                            new StreamChunk.Done("tool_calls", null));
                }
                childStarted.countDown();
                return TestStreams.of();
            }
        };
        ToolRegistry registry = new ToolRegistry();
        TaskTool task = new TaskTool();
        registry.register(task);
        try (var agent = agent(client, registry)) {
            task.bind(agent::runSubagent);
            Console console = new Console();
            console.type("/subagents\r/cancel\r");
            try (TurnHandle handle = agent.runTurn("调查项目")) {
                assertTrue(childStarted.await(3, TimeUnit.SECONDS));
                String taskId = agent.subagentSnapshots().getFirst().id();
                AtomicBoolean queriedWhileRunning = new AtomicBoolean();
                console.onRead = count -> {
                    if (count == 12) { // 第一条命令已处理，第二条命令刚开始。
                        queriedWhileRunning.set(!handle.isCancelled()
                                && console.text().contains(taskId) && console.text().contains("RUNNING"));
                    }
                };
                assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertFalse(console.app(agent).consumeTurn(handle)));
                assertTrue(queriedWhileRunning.get());
                assertTrue(handle.isCancelled());
                assertFalse(console.text().contains("未知命令"));
                assertTrue(agent.session().messages().stream().filter(m -> m.role() == Role.USER)
                        .allMatch(m -> !m.content().contains("/subagents") && !m.content().contains("/cancel")));
                parentThread.get().join(3000);
                assertFalse(parentThread.get().isAlive());
            }
        }
    }

    @Test void continuousEventsDoNotStarveRuntimeCommandsOrCtrlC() throws Exception {
        try (var agent = agent(null, new ToolRegistry())) {
            for (String command : List.of("/subagents\r/cancel\r", "\u0003")) {
                Console console = new Console();
                console.type(command);
                try (TurnHandle handle = new TurnHandle()) {
                    console.onRead = count -> TestTurnEvents.emit(handle, new TurnEvent.Notice("持续事件"));
                    assertTimeoutPreemptively(Duration.ofSeconds(3), () -> assertFalse(console.app(agent).consumeTurn(handle)));
                    assertTrue(handle.isCancelled());
                    if (command.startsWith("/")) assertTrue(console.text().contains("当前会话暂无子任务"));
                }
            }
        }
    }

    @Test void sessionChangingCommandsAreRejectedDuringTurn() throws Exception {
        try (var agent = agent(null, new ToolRegistry()); TurnHandle handle = new TurnHandle()) {
            Console console = new Console();
            String original = agent.session().id();
            var app = console.app(agent);
            for (String command : List.of("/new", "/switch other", "/exit", "普通任务", "/memory clear")) {
                app.dispatchRunningCommand(command, handle);
            }
            assertEquals(original, agent.session().id());
            assertFalse(handle.isCancelled());
            assertTrue(console.text().contains("其他命令请在本轮结束后执行"));
        }
    }

    @Test void approvalWaitsForUnfinishedRuntimeCommandInsteadOfConsumingItsSuffix() throws Exception {
        try (var agent = agent(null, new ToolRegistry()); TurnHandle handle = new TurnHandle()) {
            Console console = new Console();
            AtomicBoolean resolved = new AtomicBoolean();
            console.type("/subagents\r/cancel\r");
            console.onRead = count -> {
                if (count == 2) TestTurnEvents.emit(handle,
                        new TurnEvent.ApprovalRequest("task", "调查", answer -> resolved.set(answer.equals("n"))));
            };
            console.readLine = args -> {
                assertTrue(console.text().contains("当前会话暂无子任务"));
                assertEquals(11, console.reads);
                return "n";
            };
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> console.app(agent).consumeTurn(handle));
            assertTrue(resolved.get());
            assertTrue(handle.isCancelled());
        }
    }

    @Test void unfinishedCommandIsRestoredToIdleEditorWhenTurnEnds() throws Exception {
        try (var agent = agent(null, new ToolRegistry()); TurnHandle handle = new TurnHandle()) {
            Console console = new Console();
            console.type("/");
            console.onRead = count -> TestTurnEvents.emit(handle,
                    new TurnEvent.Done(null, "stop", false, false, null, 1));
            var app = console.app(agent);
            assertTrue(app.consumeTurn(handle));
            AtomicInteger lines = new AtomicInteger();
            console.readLine = args -> {
                if (lines.getAndIncrement() == 0) {
                    assertEquals("/", args[2]);
                    return args[2] + "subagents";
                }
                return "/exit";
            };
            app.run();
            assertTrue(console.text().contains("当前会话暂无子任务"));
            assertFalse(console.text().contains("未知命令"));
        }
    }

    @Test void inputEditingIsBoundedAndDoesNotIncludeArrowSequences() {
        var input = new RunningCommandInput();
        "/subagentx".chars().forEach(input::accept);
        input.accept(127);
        input.accept('s');
        "\u001b[A".chars().forEach(input::accept);
        assertEquals("/subagents", input.text());
        assertEquals(RunningCommandInput.Action.SUBMIT, input.accept('\r'));
        input.accept(21);
        "x".repeat(1000).chars().forEach(input::accept);
        assertEquals(512, input.text().length());
        input.accept(3);
        assertTrue(input.isEmpty());
    }

    @Test void childPreviewAndConfirmationDisplayTaskIdentity() throws Exception {
        try (var agent = agent(null, new ToolRegistry()); TurnHandle handle = new TurnHandle()) {
            Console console = new Console();
            AtomicBoolean approved = new AtomicBoolean();
            console.readLine = args -> "y";
            TestTurnEvents.emit(handle, new TurnEvent.ToolCallStart("write_file", "code.txt", "child-1"));
            TestTurnEvents.emit(handle, new TurnEvent.ToolPreview("+new", "child-1"));
            TestTurnEvents.emit(handle, new TurnEvent.ApprovalRequest("write_file", "写入 code.txt",
                    answer -> approved.set(answer.equals("y")), "child-1"));
            TestTurnEvents.emit(handle, new TurnEvent.ToolCallEnd("write_file", true, "已创建", "child-1"));
            TestTurnEvents.emit(handle, new TurnEvent.Done(null, "stop", false, false, null, 1));
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> assertTrue(console.app(agent).consumeTurn(handle)));
            assertTrue(approved.get());
            assertTrue(console.text().contains("[子任务 child-1]"));
            assertTrue(console.text().contains("修改预览"));
            assertTrue(console.text().contains("需要确认"));
        }
    }

    private static com.jaco.tool.UserQuestion question(boolean choices) {
        return new com.jaco.tool.UserQuestion("format", "格式", "配置采用哪种格式？", choices ? List.of(
                new com.jaco.tool.UserQuestion.Option("yaml", "YAML", "适合人工编辑", true),
                new com.jaco.tool.UserQuestion.Option("json", "JSON", "便于程序处理", false)) : List.of(), choices ? "支持注释" : "");
    }

    @Test void questionUiSupportsDefaultSkipChoiceFreeTextAndInvalidRetry() throws Exception {
        for (String input : List.of("\r", "/skip\r", "2\r", "自定义\r", "/text 123\r", "9\r\1772\r")) {
            try (var agent = agent(null, new ToolRegistry()); TurnHandle handle = new TurnHandle()) {
                Console console = new Console();
                AtomicBoolean typed = new AtomicBoolean();
                console.onIdle = () -> {
                    if (console.text().contains("回答 ❯") && typed.compareAndSet(false, true)) console.type(input);
                };
                var question = question(true);
                AtomicReference<com.jaco.tool.UserQuestion.Answer> answer = new AtomicReference<>();
                TestTurnEvents.emit(handle, new TurnEvent.QuestionRequest("request", question, submission -> {
                    answer.set(question.answer(submission));
                    TestTurnEvents.emit(handle, new TurnEvent.Done(null, "stop", false, false, null, 1));
                }, () -> !handle.isCancelled() && answer.get() == null));
                assertTimeoutPreemptively(Duration.ofSeconds(3), () -> assertTrue(console.app(agent).consumeTurn(handle)));
                assertNotNull(answer.get());
                if (input.equals("\r") || input.startsWith("/skip")) {
                    assertEquals("recommended_default", answer.get().source());
                    assertTrue(console.text().contains("已采用推荐项：YAML"));
                } else if (input.equals("2\r") || input.startsWith("9")) assertEquals("json", answer.get().selectedOptionIds().getFirst());
                else assertEquals("custom_text", answer.get().source());
                assertTrue(console.text().contains("支持注释"));
                assertTrue(console.text().contains("适合人工编辑"));
                assertFalse(console.text().contains("允许?"));
            }
        }
    }

    @Test void invalidAnswerRemainsVisibleAndCanBeCorrectedWithoutRetyping() throws Exception {
        try (var agent = agent(null, new ToolRegistry()); TurnHandle handle = new TurnHandle()) {
            Console console = new Console();
            AtomicInteger stage = new AtomicInteger();
            AtomicReference<com.jaco.tool.UserQuestion.Answer> answer = new AtomicReference<>();
            console.onIdle = () -> {
                if (!console.text().contains("回答 ❯") || answer.get() != null) return;
                if (stage.getAndIncrement() == 0) console.type("9\r");
                else {
                    assertNull(answer.get());
                    assertTrue(console.text().contains("请选择 1～2"));
                    assertTrue(console.text().contains("回答 ❯ 9"));
                    console.type("\1772\r");
                }
            };
            var question = question(true);
            TestTurnEvents.emit(handle, new TurnEvent.QuestionRequest("r", question, s -> {
                answer.set(question.answer(s));
                TestTurnEvents.emit(handle, new TurnEvent.Done(null, "stop", false, false, null, 1));
            }, () -> !handle.isCancelled() && answer.get() == null));
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> console.app(agent).consumeTurn(handle));
            assertEquals("json", answer.get().selectedOptionIds().getFirst());
        }
    }

    @Test void pureTextRequiresAnswerAndOversizedInputIsNotSilentlyTruncated() throws Exception {
        for (String input : List.of("\r补充说明\r", "x".repeat(2001) + "\r\025缩短答案\r", "/skip\r",
                "\033[200~第一行\r\n\r\n第二行\033[201~\r", "第一行\033\r第二行\r",
                "第一行\033[13;2u第二行\r", "第一行\033[27;2;13~第二行\r")) {
            try (var agent = agent(null, new ToolRegistry()); TurnHandle handle = new TurnHandle()) {
                Console console = new Console();
                AtomicBoolean typed = new AtomicBoolean();
                console.onIdle = () -> {
                    if (console.text().contains("回答 ❯") && typed.compareAndSet(false, true)) console.type(input);
                };
                var question = question(false);
                AtomicReference<com.jaco.tool.UserQuestion.Answer> answer = new AtomicReference<>();
                TestTurnEvents.emit(handle, new TurnEvent.QuestionRequest("r", question, submission -> {
                    answer.set(question.answer(submission));
                    TestTurnEvents.emit(handle, new TurnEvent.Done(null, "stop", false, false, null, 1));
                }, () -> !handle.isCancelled() && answer.get() == null));
                assertTimeoutPreemptively(Duration.ofSeconds(3), () -> console.app(agent).consumeTurn(handle));
                if (input.startsWith("/skip")) assertEquals("skipped", answer.get().status());
                else {
                    assertEquals("custom_text", answer.get().source());
                    String expected = input.startsWith("x") ? "缩短答案" : input.startsWith("\033[200~")
                            ? "第一行\n\n第二行" : input.startsWith("第一行") ? "第一行\n第二行" : "补充说明";
                    assertEquals(expected, answer.get().customText());
                }
                assertFalse(console.text().contains("已采用推荐项"));
            }
        }
    }

    @Test void cancelEofAndExternalCancellationNeverChooseDefaults() throws Exception {
        for (int key : List.of(3, 4, -1, -2)) {
            try (var agent = agent(null, new ToolRegistry()); TurnHandle handle = new TurnHandle()) {
                Console console = new Console();
                AtomicBoolean answered = new AtomicBoolean();
                AtomicInteger idle = new AtomicInteger();
                console.onIdle = () -> {
                    if (console.text().contains("回答 ❯") && idle.incrementAndGet() == 20) {
                        if (key == -2) handle.cancel(); else console.keys.add(key);
                    }
                };
                TestTurnEvents.emit(handle, new TurnEvent.QuestionRequest("r", question(true), s -> answered.set(true), () -> !handle.isCancelled()));
                assertTimeoutPreemptively(Duration.ofSeconds(3), () -> assertFalse(console.app(agent).consumeTurn(handle)));
                assertFalse(answered.get());
                assertFalse(console.text().contains("已采用推荐项"));
            }
        }
    }

    @Test void questionWaitsForPendingRunningInputAndStaleQuestionIsIgnored() throws Exception {
        for (boolean active : List.of(false, true)) {
            try (var agent = agent(null, new ToolRegistry()); TurnHandle handle = new TurnHandle()) {
                Console console = new Console();
                console.type("/");
                AtomicBoolean cleared = new AtomicBoolean(), typed = new AtomicBoolean(), answered = new AtomicBoolean();
                console.onIdle = () -> {
                    if (console.text().contains("有问题待回答") && cleared.compareAndSet(false, true)) console.keys.add(21);
                    if (console.text().contains("回答 ❯") && typed.compareAndSet(false, true)) console.type("2\r");
                };
                TestTurnEvents.emit(handle, new TurnEvent.QuestionRequest("r", question(true), s -> {
                    assertEquals("json", s.selectedOptionId()); answered.set(true);
                    TestTurnEvents.emit(handle, new TurnEvent.Done(null, "stop", false, false, null, 1));
                }, () -> active && !answered.get() && !handle.isCancelled()));
                if (!active) TestTurnEvents.emit(handle, new TurnEvent.Done(null, "stop", false, false, null, 1));
                assertTimeoutPreemptively(Duration.ofSeconds(3), () -> console.app(agent).consumeTurn(handle));
                assertEquals(active, answered.get());
                if (active) assertTrue(cleared.get());
                else assertFalse(console.text().contains("回答 ❯"));
            }
        }
    }

    @Test void realModelToolQuestionAnswerAndContinuationArePersistedAsToolResult() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        var ask = new com.jaco.tool.builtin.AskUserQuestionTool();
        registry.register(ask);
        AtomicInteger calls = new AtomicInteger();
        var client = new OpenAiCompatClient("http://localhost", "k") {
            @Override public ChatStream chatStream(ChatRequest request) {
                if (calls.getAndIncrement() == 0) return TestStreams.of(new StreamChunk.ToolCallDelta(0, "question", "ask_user_question", """
                        {"questions":[{"id":"format","question":"格式？","recommendation_reason":"支持注释",
                        "options":[{"id":"yaml","label":"YAML","recommended":true},{"id":"json","label":"JSON"}]}]}
                        """), new StreamChunk.Done("tool_calls", null));
                var result = request.messages().getLast();
                assertEquals(Role.TOOL, result.role());
                assertTrue(result.content().contains("recommended_default"));
                assertTrue(result.content().contains("empty_submit"));
                return TestStreams.of(new StreamChunk.Delta("采用推荐项 YAML"), new StreamChunk.Done("stop", null));
            }
        };
        try (var agent = agent(client, registry)) {
            Console console = new Console();
            AtomicBoolean typed = new AtomicBoolean();
            console.onIdle = () -> { if (console.text().contains("回答 ❯") && typed.compareAndSet(false, true)) console.type("\r"); };
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> assertTrue(console.app(agent).consumeTurn(agent.runTurn("请创建配置"))));
            assertEquals(2, calls.get());
            var loaded = new SessionStore(tmp).load(agent.session().id()).orElseThrow();
            assertEquals(1, loaded.messages().stream().filter(m -> m.role() == Role.USER).count());
            assertTrue(loaded.messages().stream().anyMatch(m -> m.role() == Role.TOOL && m.content().contains("recommended_default")));
        } finally { ask.close(); }
    }

    @Test void pasteModeCollectsLegacyMultilineInputBeforeOneExplicitSubmission() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var client = new OpenAiCompatClient("http://localhost", "k") {
            @Override public ChatStream chatStream(ChatRequest request) {
                calls.incrementAndGet();
                assertEquals("/help\n第一行\n\n第二行😀", request.messages().getLast().content());
                return TestStreams.of(new StreamChunk.Delta("收到整段提示词"), new StreamChunk.Done("stop", null));
            }
        };
        try (var agent = agent(client, new ToolRegistry())) {
            Console console = new Console();
            AtomicInteger lines = new AtomicInteger();
            console.readLine = args -> switch (lines.getAndIncrement()) {
                case 0 -> "/paste";
                case 1 -> "/help";
                case 2 -> "第一行";
                case 3 -> "";
                case 4 -> "第二行😀";
                case 5 -> "/end";
                case 6 -> {
                    assertEquals(0, calls.get());
                    assertEquals("/help\n第一行\n\n第二行😀", args[2]);
                    yield (String) args[2];
                }
                default -> "/exit";
            };
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> console.app(agent).run());
            assertEquals(1, calls.get());
            assertEquals(1, agent.session().messages().stream().filter(m -> m.role() == Role.USER).count());
        }
    }

    @Test void cancellingPasteModeDoesNotSubmitOrDispatchPastedCommands() throws Exception {
        try (var agent = agent(null, new ToolRegistry())) {
            Console console = new Console();
            AtomicInteger lines = new AtomicInteger();
            console.readLine = args -> switch (lines.getAndIncrement()) {
                case 0 -> "/paste";
                case 1 -> "/new";
                case 2 -> throw new org.jline.reader.UserInterruptException("");
                default -> "/exit";
            };
            String sessionId = agent.session().id();
            console.app(agent).run();
            assertEquals(sessionId, agent.session().id());
            assertTrue(agent.session().messages().isEmpty());
            assertTrue(console.text().contains("已取消粘贴"));
        }
    }
}
