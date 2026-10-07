package com.jaco.tool;

import com.jaco.agent.TurnEvent;
import com.jaco.agent.TurnEventSink;
import com.jaco.hook.AgentHook;
import com.jaco.hook.HookVerdict;
import com.jaco.llm.ToolCall;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 内置权限闸门（作为 hook 实现：权限是可插拔扩展点的第一个真实用户）。
 * 三道闸门：硬 deny list（不可放行）→ 只读白名单 / 会话内放行 → 交互确认（y/a/n）。
 * 拒绝原因作为 tool result 回喂模型，不中断 agent loop。
 */
public final class PermissionHook implements AgentHook {

    /** 无条件拒绝的命令模式，y/a/n 都无法放行。 */
    private static final List<Pattern> DENY_PATTERNS = List.of(
            Pattern.compile("rm\\s+(-[a-zA-Z]*[rf][a-zA-Z]*\\s+)+/\\s*$"),      // rm -rf /
            Pattern.compile("rm\\s+-[a-zA-Z]*r[a-zA-Z]*f|--no-preserve-root"),
            Pattern.compile("mkfs\\."),
            Pattern.compile(">\\s*/dev/sd[a-z]"),
            Pattern.compile("\\bdd\\b.*\\bof=/dev/(sd|nvme|hd)"),
            Pattern.compile("format\\s+[a-zA-Z]:", Pattern.CASE_INSENSITIVE),
            Pattern.compile("del\\s+/[sq]", Pattern.CASE_INSENSITIVE),
            Pattern.compile("rd\\s+/s\\s+/q", Pattern.CASE_INSENSITIVE),
            Pattern.compile("Remove-Item.*-Recurse.*-Force\\s+[A-Z]:\\\\\\s*$"),
            Pattern.compile("\\b(shutdown|reboot|poweroff)\\b"),
            Pattern.compile(":\\(\\)\\s*\\{\\s*:\\|:&\\s*\\};:"));              // fork bomb

    private static final Set<String> READ_ONLY = Set.of("read_file", "list_dir", "grep", "recall");

    private final Set<String> sessionAllowed = ConcurrentHashMap.newKeySet();

    @Override
    public HookVerdict onBeforeToolCall(ToolCall call) {
        String name = call.function().name();
        String args = String.valueOf(call.function().arguments());
        for (Pattern p : DENY_PATTERNS) {
            if (p.matcher(args).find()) {
                return HookVerdict.deny("命中危险命令黑名单: " + p.pattern());
            }
        }
        if (READ_ONLY.contains(name) || sessionAllowed.contains(name)) {
            return HookVerdict.allow();
        }
        return askUser(call);
    }

    private HookVerdict askUser(ToolCall call) {
        TurnEventSink sink = TurnEventSink.current();
        if (sink == null) {
            // 无 UI 场景（测试/嵌入）：没有确认渠道，默认拒绝，宁可保守
            return HookVerdict.deny("没有可用的确认渠道");
        }
        CompletableFuture<String> answer = new CompletableFuture<>();
        sink.emit(new TurnEvent.ApprovalRequest(call.function().name(), detailOf(call), answer::complete));
        try {
            String response = await(answer, sink);
            return switch (response) {
                case "y" -> HookVerdict.allow();
                case "a" -> {
                    sessionAllowed.add(call.function().name());
                    yield HookVerdict.allow();
                }
                default -> HookVerdict.deny("用户拒绝执行");
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return HookVerdict.deny("被用户中断");
        }
    }

    private String await(CompletableFuture<String> answer, TurnEventSink sink) throws InterruptedException {
        while (true) {
            try {
                return answer.get(200, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                if (sink.cancelled()) {
                    answer.complete("n");
                    return "n";
                }
            } catch (java.util.concurrent.ExecutionException e) {
                return "n";
            }
        }
    }

    private String detailOf(ToolCall call) {
        return switch (call.function().name()) {
            case "write_file" -> "写入文件 " + extractArg(call, "path");
            case "run_command" -> "执行命令: " + extractArg(call, "command");
            default -> String.valueOf(call.function().arguments());
        };
    }

    private String extractArg(ToolCall call, String key) {
        try {
            var args = new com.fasterxml.jackson.databind.ObjectMapper().readTree(call.function().arguments());
            return args.path(key).asText(String.valueOf(call.function().arguments()));
        } catch (Exception e) {
            return String.valueOf(call.function().arguments());
        }
    }

    /** 会话结束（/new）时清空放行记录。 */
    public void resetSession() {
        sessionAllowed.clear();
    }
}
