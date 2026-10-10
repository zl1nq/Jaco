package com.jaco.memory;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;

/** 独立于会话的有界用户画像。只有原子落盘成功后才更新内存状态。 */
public final class UserProfileStore {
    public static final int MAX_ENTRIES = 64;
    public static final int BATCH_SIZE = 10;
    private static final int MAX_FILE_BYTES = 1_000_000;
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    private final Path file;
    private Profile profile;

    public record Profile(boolean enabled, List<ProfileEntry> entries,
                          List<PendingProfileMessage> pending, long retryAfter, Map<String, Long> processedAt) {
        public Profile(boolean enabled, List<ProfileEntry> entries) {
            this(enabled, entries, List.of(), 0, Map.of());
        }
        public Profile {
            pending = pending == null ? List.of() : List.copyOf(pending);
            processedAt = processedAt == null ? Map.of() : Map.copyOf(processedAt);
        }
    }

    public UserProfileStore(Path file) throws IOException {
        this.file = file;
        if (Files.exists(file)) {
            if (Files.size(file) > MAX_FILE_BYTES) {
                throw new IOException("用户画像文件过大");
            }
            JsonNode root = MAPPER.readTree(file.toFile());
            if (root == null || !root.isObject() || !root.path("enabled").isBoolean()
                    || !root.path("entries").isArray()) {
                throw new IOException("用户画像文件格式无效");
            }
            profile = MAPPER.treeToValue(root, Profile.class);
            validateEntries(profile.entries());
            validatePending(profile.pending());
        } else {
            profile = new Profile(true, List.of());
        }
    }

    public synchronized boolean enabled() {
        return profile.enabled();
    }

    public synchronized List<ProfileEntry> entries() {
        return List.copyOf(profile.entries());
    }

    public static String projectScope(Path workspace) throws IOException {
        return workspace.toRealPath().toString();
    }

    /** 按固定白名单和本轮任务选择画像；项目值覆盖全局值。 */
    public synchronized String context(String project, Set<ProfileContextSelector.Topic> topics) throws IOException {
        if (!profile.enabled()) {
            return "";
        }
        return ProfileContextSelector.select(profile.entries(), project, topics);
    }

    public synchronized void setEnabled(boolean enabled) throws IOException {
        persist(new Profile(enabled, profile.entries(), enabled ? profile.pending() : List.of(),
                enabled ? profile.retryAfter() : 0, enabled ? profile.processedAt() : Map.of()));
    }

    public synchronized boolean forget(String id) throws IOException {
        List<ProfileEntry> next = new ArrayList<>(profile.entries());
        if (!next.removeIf(e -> e.id().equals(id))) {
            return false;
        }
        // 手动遗忘时丢弃旧候选消息，避免后续批处理又记回已经删除的信息。
        persist(new Profile(profile.enabled(), List.copyOf(next)));
        return true;
    }

    public synchronized void clear() throws IOException {
        persist(new Profile(profile.enabled(), List.of()));
    }

    public synchronized int pendingCount(String project) {
        return (int) profile.pending().stream().filter(m -> m.project().equals(project)).count();
    }

    public synchronized boolean retryReady() {
        return System.currentTimeMillis() >= profile.retryAfter();
    }

    public synchronized void markExtractionFailed() throws IOException {
        persist(new Profile(profile.enabled(), profile.entries(), profile.pending(), System.currentTimeMillis() + 60_000,
                profile.processedAt()));
    }

    public synchronized void enqueue(String prompt, String previousAssistant, String session, String project)
            throws IOException {
        if (!profile.enabled() || !ProfileExtractionPolicy.shouldQueue(prompt)) return;
        List<PendingProfileMessage> next = new ArrayList<>(profile.pending());
        long createdAt = Math.max(System.currentTimeMillis(), profile.pending().stream()
                .mapToLong(PendingProfileMessage::createdAt).max().orElse(0) + 1);
        createdAt = Math.max(createdAt, profile.processedAt().values().stream().mapToLong(Long::longValue).max().orElse(0) + 1);
        next.add(new PendingProfileMessage(UUID.randomUUID().toString(), session, project, prompt, previousAssistant, createdAt));
        validatePending(next);
        persist(new Profile(profile.enabled(), profile.entries(), List.copyOf(next), profile.retryAfter(), profile.processedAt()));
    }

    /** 按时间顺序分批，最多十条、合计二万字符；不同项目绝不混在同一请求中。 */
    public synchronized List<PendingProfileMessage> pendingBatch(String project) {
        List<PendingProfileMessage> batch = new ArrayList<>();
        int chars = 0;
        for (var message : profile.pending()) {
            if (!message.project().equals(project)) continue;
            int size = message.message().length() + message.previousAssistant().length();
            if (batch.size() == BATCH_SIZE || chars + size > 20_000) break;
            batch.add(message);
            chars += size;
        }
        return List.copyOf(batch);
    }

    /** 严格校验整批操作，任何无效项都不落盘。证据必须来自本轮用户原始消息。 */
    public synchronized List<String> apply(String json, String prompt, String session, String project)
            throws IOException {
        return applyOperations(json, List.of(new PendingProfileMessage("single", session, project, prompt, "",
                System.currentTimeMillis())), project, false);
    }

    /** 画像和处理进度在一次原子落盘中提交，包括空 operations 的成功提取。 */
    public synchronized List<String> applyBatch(String json, List<PendingProfileMessage> batch, String project)
            throws IOException {
        if (batch.isEmpty() || !batch.equals(pendingBatch(project))) {
            throw new IOException("待处理画像批次已经变化");
        }
        return applyOperations(json, batch, project, true);
    }

    private List<String> applyOperations(String json, List<PendingProfileMessage> sources, String project, boolean batch)
            throws IOException {
        if (!profile.enabled()) {
            return List.of();
        }
        JsonNode root = MAPPER.readTree(json);
        if (root == null || !root.isObject() || root.size() != 1
                || !root.path("operations").isArray() || root.path("operations").size() > 16) {
            throw new IOException("记忆提取结果格式无效");
        }
        List<ProfileEntry> next = new ArrayList<>(profile.entries());
        List<String> changes = new ArrayList<>();
        Map<String, Long> processedAt = new HashMap<>(profile.processedAt());
        long now = System.currentTimeMillis();
        record Operation(JsonNode node, PendingProfileMessage source) { }
        List<Operation> operations = new ArrayList<>();
        for (JsonNode node : root.path("operations")) {
            PendingProfileMessage source = sources.get(0);
            if (batch) {
                String id = text(node, "message_id", 36);
                source = sources.stream().filter(m -> m.id().equals(id)).findFirst()
                        .orElseThrow(() -> new IOException("记忆来源消息 ID 无效"));
            }
            operations.add(new Operation(node, source));
        }
        // 同一批次对同一偏好的更新按用户发言顺序应用，较新的表达最终生效。
        operations.sort(java.util.Comparator.comparingInt(op -> sources.indexOf(op.source())));
        for (Operation operation : operations) {
            JsonNode op = operation.node();
            PendingProfileMessage source = operation.source();
            String action = text(op, "action", 10);
            String category = text(op, "category", 16);
            String key = ProfileContextSelector.canonicalKey(text(op, "key", 64));
            String scope = text(op, "scope", 10);
            String evidence = text(op, "evidence", 300);
            if (!List.of("fact", "preference").contains(category)
                    || !key.matches("[a-z][a-z0-9_.-]{0,63}")
                    || !List.of("global", "project").contains(scope) || !source.message().contains(evidence)) {
                throw new IOException("记忆操作或用户原文证据无效");
            }
            String targetScope = scope.equals("global") ? "global" : project;
            if (!action.equals("set") && !action.equals("delete")) throw new IOException("未知记忆操作");
            String value = action.equals("set") ? text(op, "value", 240) : null;
            String revisionKey = category + ":" + key + ":" + targetScope;
            // 其他项目的旧候选可能稍后才处理；不能覆盖较新偏好或重建已删除条目。
            if (source.createdAt() < processedAt.getOrDefault(revisionKey, 0L)) continue;
            processedAt.put(revisionKey, source.createdAt());
            var matching = next.stream().filter(e -> e.category().equals(category)
                    && ProfileContextSelector.canonicalKey(e.key()).equals(key) && e.scope().equals(targetScope)).toList();
            ProfileEntry previous = matching.stream().max(java.util.Comparator.comparingLong(ProfileEntry::updatedAt))
                    .orElse(null);
            if (action.equals("delete")) {
                if (previous != null) {
                    next.removeAll(matching);
                    changes.add("已忘记 " + previous.key());
                }
            } else if (action.equals("set")) {
                if (previous != null && matching.size() == 1 && previous.key().equals(key) && previous.value().equals(value)) {
                    continue;
                }
                if (previous != null) {
                    next.removeAll(matching);
                }
                next.add(new ProfileEntry(previous == null ? UUID.randomUUID().toString() : previous.id(),
                        category, key, value, targetScope, source.session(), evidence,
                        previous == null ? now : previous.createdAt(), now));
                changes.add((previous == null ? "已记住 " : "已更新 ") + key + "：" + value
                        + (scope.equals("project") ? "（当前项目）" : "（全局）"));
            }
        }
        List<PendingProfileMessage> remaining = new ArrayList<>(profile.pending());
        if (batch) remaining.removeAll(sources);
        // 只在还有更早候选时保留版本信息，避免遗忘标记无限累积。
        processedAt.entrySet().removeIf(e -> remaining.stream().noneMatch(m -> m.createdAt() < e.getValue()));
        if (!changes.isEmpty() || batch || !processedAt.equals(profile.processedAt())) {
            validateEntries(next);
            persist(new Profile(profile.enabled(), List.copyOf(next), List.copyOf(remaining), batch ? 0 : profile.retryAfter(),
                    Map.copyOf(processedAt)));
        }
        return List.copyOf(changes);
    }

    private static String text(JsonNode node, String field, int limit) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()
                || value.textValue().length() > limit || value.textValue().chars().anyMatch(Character::isISOControl)) {
            throw new IOException("记忆字段无效: " + field);
        }
        return value.textValue();
    }

    private static void validateEntries(List<ProfileEntry> entries) throws IOException {
        if (entries == null || entries.size() > MAX_ENTRIES) {
            throw new IOException("用户画像最多保存 " + MAX_ENTRIES + " 条");
        }
        var ids = new java.util.HashSet<String>();
        var keys = new java.util.HashSet<String>();
        for (ProfileEntry entry : entries) {
            if (entry == null) {
                throw new IOException("用户画像包含空条目");
            }
            JsonNode node = MAPPER.valueToTree(entry);
            String id = text(node, "id", 36);
            String category = text(node, "category", 16);
            String key = text(node, "key", 64);
            String scope = text(node, "scope", 4096);
            text(node, "value", 240);
            text(node, "evidence", 300);
            text(node, "sourceSession", 100);
            if (!ids.add(id) || !keys.add(category + ":" + key + ":" + scope)
                    || !List.of("fact", "preference").contains(category)
                    || !key.matches("[a-z][a-z0-9_.-]{0,63}")
                    || (!scope.equals("global") && !Path.of(scope).isAbsolute())) {
                throw new IOException("用户画像条目无效或重复");
            }
        }
    }

    private void persist(Profile next) throws IOException {
        byte[] data = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(next);
        if (data.length > MAX_FILE_BYTES) {
            throw new IOException("用户画像文件过大");
        }
        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, "user-profile-", ".tmp");
        try {
            Files.write(temp, data);
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            profile = next;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void validatePending(List<PendingProfileMessage> pending) throws IOException {
        if (pending.size() > 100) throw new IOException("画像待处理消息最多 100 条");
        int chars = 0;
        Set<String> ids = new java.util.HashSet<>();
        for (var message : pending) {
            if (message == null) throw new IOException("待处理消息无效");
            JsonNode node = MAPPER.valueToTree(message);
            String id = text(node, "id", 36);
            text(node, "session", 100);
            String project = text(node, "project", 4096);
            if (!ids.add(id) || !Path.of(project).isAbsolute() || message.message() == null
                    || message.message().isBlank() || message.message().length() > 16_000
                    || message.previousAssistant() == null || message.previousAssistant().length() > 2000 || message.createdAt() < 1) {
                throw new IOException("待处理消息无效或输入过长");
            }
            chars += message.message().length() + message.previousAssistant().length();
        }
        if (chars > 100_000) throw new IOException("画像待处理消息总长度超过 100000 字符");
    }
}
