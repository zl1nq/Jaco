package com.jaco.agent;

import com.jaco.llm.Message;
import com.jaco.llm.Role;
import com.jaco.session.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 上下文压缩器。在轮开始时调用，循环本体不感知。单条超大工具输出由 AgentRunner 的
 * 回填闸门在入口处截断落盘（不进入压缩周期），这里只管整轮级别的瘦身：
 *
 * <p>① 旧轮（最近 2 轮之外）的 tool_result 替换为占位——tool_calls/tool_result 配对原子保留
 * ② 中段整轮归档 JSONL，原位置留标记
 * ③ LLM 摘要兜底：② 仍不达标时总结归档内容替换标记
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
        notices.accept("⟲ 已压缩: " + before + " → " + after + " tokens（全量历史归档于 " + sessions.archivePath(session).getFileName() + "）");
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

        // ① 旧轮的 tool_result 替换为占位（配对消息保留，块作为原子组不拆）
        List<List<Message>> recent = blocks.subList(blocks.size() - KEEP_RECENT_TURNS, blocks.size());
        List<List<Message>> old = new ArrayList<>(blocks.subList(0, blocks.size() - KEEP_RECENT_TURNS));
        for (List<Message> block : old) {
            for (int i = 0; i < block.size(); i++) {
                Message m = block.get(i);
                if (m.role() == Role.TOOL) {
                    block.set(i, Message.toolResult(m.toolCallId(), "(工具结果已省略)"));
                }
            }
        }
        long tokens = rebuild(messages, concat(old, recent));
        if (tokens <= target) {
            return tokens;
        }

        // ② 旧轮整体归档，原位置留标记
        List<Message> oldFlat = flatten(old);
        sessions.appendArchive(session, oldFlat);
        List<Message> replacement = new ArrayList<>();
        replacement.add(Message.user("[早前 " + oldFlat.size() + " 条消息已压缩归档，关键内容可用 recall 工具检索]"));
        tokens = rebuild(messages, concat(List.of(replacement), recent));
        if (tokens <= target) {
            return tokens;
        }

        // ③ LLM 摘要兜底：总结归档内容替换标记
        if (summarizer != null) {
            try {
                String summary = summarizer.summarize(oldFlat);
                replacement.set(0, Message.user("[历史摘要]\n" + summary));
                tokens = rebuild(messages, concat(List.of(replacement), recent));
            } catch (Exception e) {
                log.warn("摘要压缩失败，保留归档标记: {}", e.getMessage());
            }
        }
        return tokens;
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
