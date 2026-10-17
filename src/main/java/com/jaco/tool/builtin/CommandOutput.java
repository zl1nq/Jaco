package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaco.session.SessionStore;
import com.jaco.tool.ToolException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/** 持续消费输出，内存有界；超长输出按块写入现有会话归档，可通过 recall 读取。 */
final class CommandOutput {
    static final int PREVIEW_LIMIT = 50_000;
    static final int ARCHIVE_LIMIT = 2_000_000;
    private static final int CHUNK_SIZE = 16_000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path archive;
    private final boolean archiveAll;
    private final String id = "command-" + UUID.randomUUID();
    private final StringBuilder tail = new StringBuilder();
    private final StringBuilder pending = new StringBuilder();
    private boolean archiving;
    private long total;
    private int stored;
    private int parts;
    private String archiveError;
    private String readError;
    private boolean archiveLimited;
    private boolean finished;

    CommandOutput(Path archive) {
        this(archive, false);
    }

    CommandOutput(Path archive, boolean archiveAll) {
        this.archive = archive;
        this.archiveAll = archiveAll;
    }

    synchronized boolean append(char[] chars, int length) {
        if (finished) {
            return false;
        }
        total += length;
        tail.append(chars, 0, length);
        if (tail.length() > PREVIEW_LIMIT) {
            tail.delete(0, tail.length() - PREVIEW_LIMIT);
        }
        if (archiveError != null || (archive == null && total > PREVIEW_LIMIT)) {
            pending.setLength(0);
            return true;
        }
        int keep = Math.min(length, ARCHIVE_LIMIT - stored - pending.length());
        pending.append(chars, 0, keep);
        archiveLimited |= keep < length;
        if ((archiveAll || total > PREVIEW_LIMIT) && archive != null) {
            try {
                archiving = true;
                while (pending.length() >= CHUNK_SIZE) {
                    int end = CHUNK_SIZE;
                    if (Character.isHighSurrogate(pending.charAt(end - 1))) {
                        end--;
                    }
                    writeChunk(end);
                }
            } catch (IOException e) {
                archiveError = e.getClass().getSimpleName();
                pending.setLength(0);
            }
        }
        return true;
    }

    private void writeChunk(int length) throws IOException {
        String content = "[" + id + " part " + (++parts) + "]\n" + pending.substring(0, length);
        SessionStore.appendArchiveLine(archive, MAPPER.writeValueAsString(Map.of("archived", "tool_output", "content", content)));
        stored += length;
        pending.delete(0, length);
    }

    synchronized void readFailed(IOException error) {
        if (!finished) {
            readError = error.getClass().getSimpleName();
        }
    }

    synchronized void finish() {
        if (finished) {
            return;
        }
        finished = true;
        if (!archiving) {
            return;
        }
        try {
            if (archiveError == null && !pending.isEmpty()) {
                int length = pending.length();
                if (Character.isHighSurrogate(pending.charAt(length - 1))) {
                    length--;
                }
                if (length > 0) {
                    writeChunk(length);
                }
            }
        } catch (IOException e) {
            archiveError = e.getClass().getSimpleName();
        } finally {
            archiving = false;
            pending.setLength(0);
        }
    }

    synchronized String metadata() {
        StringBuilder result = new StringBuilder("output_chars: " + total
                + "\noutput_truncated: " + (total > PREVIEW_LIMIT) + "\n");
        if (total > PREVIEW_LIMIT || archiveAll) {
            if (archive != null && (stored > 0 || (archiveAll && archiveError == null))) {
                result.append("archive_id: ").append(id).append("\narchive_truncated: ").append(archiveLimited)
                        .append("\n(用 recall 的 query 检索 archive_id，再按 entry_id 读取各 part；未满块的日志在结束时归档)\n");
            } else {
                result.append("(无可用输出归档，仅保留末尾预览)\n");
            }
            if (archiveLimited) {
                result.append("(日志达到 ").append(ARCHIVE_LIMIT).append(" 字符上限，超额部分继续消费但不保存)\n");
            }
        }
        if (archiveError != null) {
            result.append("(输出归档不完整: ").append(archiveError).append(")\n");
        }
        if (readError != null) {
            result.append("(输出读取不完整: ").append(readError).append(")\n");
        }
        return result.toString();
    }

    synchronized String render() {
        String text = tail.toString();
        if (!text.isEmpty() && Character.isLowSurrogate(text.charAt(0))) {
            text = text.substring(1);
        }
        return metadata() + "\n" + (text.isBlank() ? "(no output)" : text);
    }

    record Page(long total, long availableFrom, long nextOffset, boolean truncated, boolean hasMore, String text, String metadata) {
    }

    /** 偏移为整个输出的 UTF-16 字符位置；缓冲淘汰后明确返回实际起点，不静默丢失日志。 */
    synchronized Page page(long offset, int limit) {
        if (offset > total) {
            throw new ToolException("offset 超过当前输出长度 " + total);
        }
        long base = total - tail.length();
        long available = base;
        if (!tail.isEmpty() && Character.isLowSurrogate(tail.charAt(0))) {
            available++;
        }
        long start = Math.max(offset, available);
        int from = (int) (start - base);
        if (from < tail.length() && from > 0 && Character.isLowSurrogate(tail.charAt(from))
                && Character.isHighSurrogate(tail.charAt(from - 1))) {
            throw new ToolException("offset 位于 Unicode 字符中间，请使用上次返回的 next_offset");
        }
        int end = (int) Math.min(tail.length(), (long) from + limit);
        if (end > from && Character.isHighSurrogate(tail.charAt(end - 1))) {
            end--;
        }
        if (end == from && from < tail.length() && limit < 2) {
            throw new ToolException("limit 太小，读取完整 Unicode 字符至少需要 2");
        }
        return new Page(total, available, base + end, offset < available, base + end < total,
                tail.substring(from, end), metadata());
    }

    synchronized long length() {
        return total;
    }
}
