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
        Function<Object[], String> readLine = args -> { throw new EndOfFileException(); };
        int reads;
        final NonBlockingReader input = new NonBlockingReader() {
            @Override protected int read(long timeout, boolean peek) {
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
}
