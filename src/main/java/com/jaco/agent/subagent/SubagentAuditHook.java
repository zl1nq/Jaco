package com.jaco.agent.subagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaco.hook.AgentHook;
import com.jaco.hook.HookVerdict;
import com.jaco.llm.ToolCall;
import com.jaco.session.SessionStore;
import com.jaco.tool.ToolExecution;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import static com.jaco.agent.AgentLoopSupport.unicodePrefix;

/** edit 子任务的独立审计；执行前记录失败即阻止写入，执行后失败不隐瞒已发生的修改。 */
final class SubagentAuditHook implements AgentHook {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path file;
    private final SubagentSpec spec;
    private final String taskId;
    private long sequence;
    private String preview;
    private boolean prepared;

    SubagentAuditHook(Path file, SubagentSpec spec, String taskId) {
        this.file = file;
        this.spec = spec;
        this.taskId = taskId;
        append(base("started"));
    }

    @Override public void onToolPrepared(ToolCall call, String preview) {
        sequence++;
        this.preview = preview;
        prepared = true;
    }

    @Override public HookVerdict onBeforeToolCall(ToolCall call) {
        append(record("prepared", call));
        return HookVerdict.allow();
    }

    @Override public void onToolCompleted(ToolCall call, ToolExecution execution) {
        if (!prepared) sequence++;
        var record = record("completed", call);
        record.put("status", execution.status().name());
        text(record, "result", execution.result(), 4096);
        try { append(record); }
        finally { prepared = false; preview = null; }
    }

    @Override public void onToolPermission(ToolCall call, HookVerdict verdict) {
        var record = record("permission", call);
        record.put("decision", verdict.proceed() ? "ALLOW" : "DENY");
        if (verdict.denyReason() != null) record.put("reason", unicodePrefix(verdict.denyReason(), 1024));
        append(record);
    }

    private Map<String, Object> base(String phase) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("timestamp", System.currentTimeMillis());
        record.put("phase", phase);
        record.put("parent_session_id", spec.parentSessionId());
        record.put("parent_turn_id", spec.parentTurnId());
        record.put("task_id", taskId);
        record.put("profile", spec.profile().id());
        record.put("tools", spec.tools().stream().sorted().toList());
        return record;
    }

    private Map<String, Object> record(String phase, ToolCall call) {
        var record = base(phase);
        record.put("sequence", sequence);
        record.put("tool_call_id", call.id());
        record.put("tool", call.function().name());
        text(record, "arguments", call.function().arguments(), 4096);
        if (preview != null) text(record, "preview", preview, 8000);
        try {
            var args = mapper.readTree(call.function().arguments());
            if (args != null && args.isObject()) {
                for (String field : new String[]{"path", "expected_version"}) {
                    if (args.path(field).isTextual()) record.put(field, unicodePrefix(args.path(field).textValue(), 1024));
                }
            }
        } catch (IOException | IllegalArgumentException ignored) { /* 无效参数仍保留原文摘要及哈希。 */ }
        return record;
    }

    private void text(Map<String, Object> record, String key, String text, int limit) {
        if (text == null) return;
        record.put(key, unicodePrefix(text, limit));
        record.put(key + "_truncated", text.length() > limit);
        try {
            record.put(key + "_sha256", java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private void append(Map<String, Object> record) {
        boolean interrupted = Thread.interrupted();
        try {
            SessionStore.appendArchiveLine(file, mapper.writeValueAsString(record));
        } catch (IOException e) {
            throw new UncheckedIOException("子任务审计写入失败；此前已执行的修改不会回滚: " + file, e);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
