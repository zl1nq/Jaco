package com.jaco.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaco.llm.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/** 会话以 JSON 形式存储在 ~/.jaco/sessions/ 下，每轮对话后原子落盘。 */
public final class SessionStore {

    private static final Logger log = LoggerFactory.getLogger(SessionStore.class);
    private static final DateTimeFormatter ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path dir;
    private final ObjectMapper mapper = new ObjectMapper();

    public SessionStore(Path dir) throws IOException {
        this.dir = dir;
        Files.createDirectories(dir);
    }

    public Session createNew() {
        LocalDateTime now = LocalDateTime.now();
        Session session = Session.create("s" + now.format(ID_FORMAT), System.currentTimeMillis());
        save(session);
        return session;
    }

    /** 启动时恢复最近一次会话；没有任何历史时开新会话。 */
    public Session loadLatestOrNew() {
        Optional<Path> latest = listSessionFiles().stream()
                .max(Comparator.comparing(p -> p.getFileName().toString()));
        if (latest.isPresent()) {
            try {
                return mapper.readValue(latest.get().toFile(), Session.class);
            } catch (IOException e) {
                // 损坏的会话文件不阻塞启动
                log.warn("跳过损坏的会话文件 {}: {}", latest.get().getFileName(), e.getMessage());
            }
        }
        return createNew();
    }

    public void save(Session session) {
        Path target = dir.resolve(session.id() + ".json");
        Path tmp = dir.resolve(session.id() + ".json.tmp");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), session);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("会话保存失败: " + target, e);
        }
    }

    /** 上下文压缩归档文件（JSONL，追加式）。 */
    public Path archivePath(Session session) {
        return dir.resolve(session.id() + ".archive.jsonl");
    }

    /** 把被压缩移出的消息追加到归档文件。 */
    public void appendArchive(Session session, List<Message> messages) {
        if (messages.isEmpty()) {
            return;
        }
        try (var writer = Files.newBufferedWriter(archivePath(session), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            for (Message m : messages) {
                writer.write(mapper.writeValueAsString(m));
                writer.write("\n");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("归档写入失败: " + archivePath(session), e);
        }
    }

    /** 把单个超大工具输出追加到归档文件。 */
    public void appendArchivedOutput(Session session, String output) {
        try (var writer = Files.newBufferedWriter(archivePath(session), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            writer.write(mapper.writeValueAsString(Map.of("archived", "tool_output", "content", output)));
            writer.write("\n");
        } catch (IOException e) {
            throw new UncheckedIOException("归档写入失败: " + archivePath(session), e);
        }
    }

    public List<Message> messagesOf(Session session) {
        return session.messages();
    }

    private List<Path> listSessionFiles() {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .filter(p -> !p.getFileName().toString().endsWith(".tmp"))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
