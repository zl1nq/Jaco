package com.jaco.agent;

import com.jaco.llm.Message;
import com.jaco.llm.Role;
import com.jaco.llm.ToolCall;
import com.jaco.session.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 上下文压缩器。在轮开始时调用，循环本体不感知。单条超大工具输出由 AgentRunner 的
 * 回填闸门在入口处截断落盘（不进入压缩周期），这里只管整轮级别的瘦身：
 *
 * <p>① 旧轮（最近 2 轮之外）的 tool_result 替换为一行摘要占位（工具名 + 体量 + 首行）——
 * tool_calls/tool_result 配对原子保留；被替换的原文先归档，成功后才修改会话
 * ② 中段整轮归档 JSONL（已归档的工具原文不重复写入），原位置留每轮一行的
 * 骨架摘要标记
 * ③ LLM 摘要兜底：② 仍不达标时总结归档内容替换标记，失败则骨架标记本身就是降级文案
 *
 * <p>度量：上次调用的真实 promptTokens 与字符估算取大者；触发 70%，目标 40%。
 * 另有 forceCompact：provider 报上下文超限时跳过触发阈值直接压，作为最后保险。
 */
public final class ContextCompactor {

    private static final Logger log = LoggerFactory.getLogger(ContextCompactor.class);

    private static final double TRIGGER_RATIO = 0.7;
    private static final double TARGET_RATIO = 0.4;
    private static final int KEEP_RECENT_TURNS = 2;

    private final SessionStore sessions;
    private final com.jaco.session.Session session;
    private final long contextLimit;

    public ContextCompactor(SessionStore sessions, com.jaco.session.Session session, long contextLimit) {
        this.sessions = sessions;
        this.session = session;
        this.contextLimit = contextLimit;
    }

    public interface Summarizer {
        String summarize(List<Message> oldMessages);
    }

    public record Outcome(boolean compacted, long tokensBefore, long tokensAfter) {
    }

    public Outcome compactIfNeeded(List<Message> messages, long lastPromptTokens,
                                   Consumer<String> notices, Summarizer summarizer) {
        long before = Math.max(estimateTokens(messages), lastPromptTokens);
        long trigger = (long) (contextLimit * TRIGGER_RATIO);
        long target = (long) (contextLimit * TARGET_RATIO);
        if (before <= trigger) {
            return new Outcome(false, before, before);
        }
        notices.accept("⟲ 上下文接近上限（约 " + before + " tokens），正在压缩…");
        long after = compact(messages, target, summarizer);
        log.info("上下文压缩: {} -> {} tokens (limit={})", before, after, contextLimit);
        notices.accept("⟲ 已压缩: " + before + " → " + after + " tokens（归档文件: " + sessions.archivePath(session).getFileName() + "）");
        return new Outcome(true, before, after);
    }

    /** provider 报上下文超限后的强制压缩：跳过触发阈值，直接压到目标比例。 */
    public Outcome forceCompact(List<Message> messages, Consumer<String> notices, Summarizer summarizer) {
        long before = estimateTokens(messages);
        notices.accept("⟲ 上下文超限，正在强制压缩…");
        long after = compact(messages, (long) (contextLimit * TARGET_RATIO), summarizer);
        log.info("强制压缩: {} -> {} tokens (limit={})", before, after, contextLimit);
        notices.accept("⟲ 已强制压缩: " + before + " → " + after + " tokens");
        return new Outcome(true, before, after);
    }

    private long compact(List<Message> messages, long target, Summarizer summarizer) {
        List<List<Message>> blocks = splitTurns(messages);

        if (blocks.size() <= KEEP_RECENT_TURNS) {
            return rebuild(messages, blocks);
        }

        List<List<Message>> recent = blocks.subList(blocks.size() - KEEP_RECENT_TURNS, blocks.size());
        List<List<Message>> old = new ArrayList<>(blocks.subList(0, blocks.size() - KEEP_RECENT_TURNS));
        // 先在临时块中压缩，归档成功前不修改会话。
        List<List<Message>> pristine = new ArrayList<>();
        for (List<Message> block : old) {
            pristine.add(List.copyOf(block));
        }

        // ① 旧轮的 tool_result 替换为一行摘要占位（配对消息保留，块作为原子组不拆）
        List<Message> replacedResults = new ArrayList<>();
        for (List<Message> block : old) {
            Map<String, String> toolNames = toolNamesIn(block);
            for (int i = 0; i < block.size(); i++) {
                Message m = block.get(i);
                if (m.role() == Role.TOOL && !session.compactedToolCallIds().contains(m.toolCallId())) {
                    replacedResults.add(m);
                    block.set(i, Message.toolResult(m.toolCallId(), placeholder(toolNames.get(m.toolCallId()), m.content())));
                }
            }
        }
        long tokens = estimateTokens(flatten(concat(old, recent)));
        if (tokens <= target) {
            sessions.appendArchive(session, replacedResults);
            for (Message m : replacedResults) {
                session.compactedToolCallIds().add(m.toolCallId());
            }
            return rebuild(messages, concat(old, recent));
        }

        // ② 原始旧轮整体归档，原位置留骨架摘要标记
        List<Message> oldFlat = flatten(pristine);
        sessions.appendArchive(session, oldFlat.stream()
                .filter(m -> m.role() != Role.TOOL
                        || !session.compactedToolCallIds().contains(m.toolCallId()))
                .toList());
        for (Message m : oldFlat) {
            if (m.role() == Role.TOOL) {
                session.compactedToolCallIds().remove(m.toolCallId());
            }
        }
        List<Message> replacement = new ArrayList<>();
        replacement.add(Message.user(skeletonDigest(pristine)));
        tokens = rebuild(messages, concat(List.of(replacement), recent));
        if (tokens <= target) {
            return tokens;
        }

        // ③ LLM 摘要兜底：总结归档内容替换标记；失败时骨架标记即降级文案
        if (summarizer != null) {
            try {
                String summary = summarizer.summarize(oldFlat);
                replacement.set(0, Message.user("[历史摘要]\n" + summary));
                tokens = rebuild(messages, concat(List.of(replacement), recent));
            } catch (Exception e) {
                log.warn("摘要压缩失败，保留骨架标记: {}", e.getMessage());
            }
        }
        return tokens;
    }

    /** 收集块内 assistant.tool_calls 的 toolCallId → 工具名，供占位摘要引用。 */
    private static Map<String, String> toolNamesIn(List<Message> block) {
        Map<String, String> names = new HashMap<>();
        for (Message m : block) {
            if (m.toolCalls() != null) {
                for (ToolCall tc : m.toolCalls()) {
                    if (tc.id() != null && tc.function() != null) {
                        names.put(tc.id(), tc.function().name());
                    }
                }
            }
        }
        return names;
    }

    /**
     * 占位摘要行：工具名 + 原始体量 + 首行。首行往往是关键信息（错误首行、exit code、
     * "(no output)"），模型据此判断是否需要 recall 取回全文。
     */
    private static String placeholder(String toolName, String content) {
        String name = toolName == null ? "工具" : toolName;
        if (content == null || content.isEmpty()) {
            return "(" + name + " 结果已省略：空输出)";
        }
        String firstLine = content.lines().findFirst().orElse("");
        if (firstLine.length() > 120) {
            firstLine = firstLine.substring(0, 120) + "…";
        }
        return "(" + name + " 结果已省略：原 " + content.lines().count() + " 行 / " + content.length()
                + " 字符；首行: " + firstLine.replace('\n', ' ') + ")";
    }

    /**
     * 归档标记：每轮一行"user 首句 → assistant 首句"骨架。是模型回忆旧对话的最低限度线索，
     * 也是③摘要失败时的现成降级文案。
     */
    private static String skeletonDigest(List<List<Message>> blocks) {
        StringBuilder sb = new StringBuilder("[早前 ").append(blocks.size()).append(" 轮对话已压缩归档，可用 recall 工具检索：");
        for (List<Message> block : blocks) {
            String user = firstText(block, Role.USER);
            String assistant = firstText(block, Role.ASSISTANT);
            if (user.isEmpty() && assistant.isEmpty()) {
                continue;
            }
            sb.append("\n· ");
            if (!user.isEmpty()) {
                sb.append("用户: ").append(user);
            }
            if (!assistant.isEmpty()) {
                sb.append(user.isEmpty() ? "助手: " : " → 助手: ").append(assistant);
            }
        }
        return sb.append("]").toString();
    }

    /** 块内该角色第一条非空文本，截 80 字符；压缩器自己注入的标记行不作为线索重复出现。 */
    private static String firstText(List<Message> block, Role role) {
        for (Message m : block) {
            if (m.role() != role || m.content() == null || m.content().isBlank()) {
                continue;
            }
            String flat = m.content().replace('\n', ' ').trim();
            if (flat.startsWith("[早前 ") || flat.startsWith("[历史摘要]")) {
                continue;
            }
            return flat.length() > 80 ? flat.substring(0, 80) + "…" : flat;
        }
        return "";
    }

    /** 按 user 消息切轮；tool/assistant 消息归属其前的 user 消息所在块（配对天然不被切断）。 */
    private List<List<Message>> splitTurns(List<Message> messages) {
        List<List<Message>> blocks = new ArrayList<>();
        for (Message m : messages) {
            if (m.role() == Role.USER || blocks.isEmpty()) {
                blocks.add(new ArrayList<>());
            }
            blocks.get(blocks.size() - 1).add(m);
        }
        return blocks;
    }

    private long rebuild(List<Message> target, List<List<Message>> blocks) {
        target.clear();
        for (List<Message> block : blocks) {
            target.addAll(block);
        }
        return estimateTokens(target);
    }

    private List<Message> flatten(List<List<Message>> blocks) {
        List<Message> all = new ArrayList<>();
        blocks.forEach(all::addAll);
        return all;
    }

    private List<List<Message>> concat(List<List<Message>> a, List<List<Message>> b) {
        List<List<Message>> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    /**
     * token 估算：CJK 字符 ≈ 1 token/字，其他 ≈ 4 字符/token，外加每条消息的协议开销。
     * 比"总长÷4"对中文场景准得多。
     */
    public static long estimateTokens(List<Message> messages) {
        long cjk = 0;
        long other = 0;
        for (Message m : messages) {
            cjk += count(m.content(), true);
            other += count(m.content(), false);
            if (m.toolCalls() != null) {
                for (var tc : m.toolCalls()) {
                    if (tc.function() != null && tc.function().arguments() != null) {
                        String args = tc.function().arguments();
                        cjk += count(args, true);
                        other += count(args, false);
                    }
                }
            }
        }
        return cjk + other / 4 + messages.size() * 4L;
    }

    private static long count(String s, boolean cjk) {
        if (s == null) {
            return 0;
        }
        long n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean isCjk = c >= 0x2E80 && c <= 0x9FFF || c >= 0xF900 && c <= 0xFAFF || c >= 0xFF00 && c <= 0xFFEF;
            if (isCjk == cjk) {
                n++;
            }
        }
        return n;
    }
}
