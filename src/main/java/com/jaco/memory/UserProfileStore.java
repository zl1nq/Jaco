package com.jaco.memory;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 独立于会话的有界用户画像。只有原子落盘成功后才更新内存状态。 */
public final class UserProfileStore {
    public static final int MAX_ENTRIES = 64;
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    private final Path file;
    private Profile profile;

    public record Profile(boolean enabled, List<ProfileEntry> entries) {
    }

    public UserProfileStore(Path file) throws IOException {
        this.file = file;
        if (Files.exists(file)) {
            if (Files.size(file) > 200_000) {
                throw new IOException("用户画像文件过大");
            }
            JsonNode root = MAPPER.readTree(file.toFile());
            if (root == null || !root.isObject() || !root.path("enabled").isBoolean()
                    || !root.path("entries").isArray()) {
                throw new IOException("用户画像文件格式无效");
            }
            profile = MAPPER.treeToValue(root, Profile.class);
            validateEntries(profile.entries());
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

    /** 同类别同键的项目值覆盖全局值；不加载其他项目的记录。 */
    public synchronized String context(String project) throws IOException {
        if (!profile.enabled()) {
            return "";
        }
        Map<String, ProfileEntry> selected = new LinkedHashMap<>();
        for (String scope : List.of("global", project)) {
            for (ProfileEntry entry : profile.entries()) {
                if (scope.equals(entry.scope())) {
                    selected.put(entry.category() + ":" + entry.key(), entry);
                }
            }
        }
        // 整条截取，避免生成破损 JSON；最近更新的条目优先进入有限上下文。
        List<Map<String, String>> bounded = new ArrayList<>();
        var data = selected.values().stream().sorted((a, b) -> Long.compare(b.updatedAt(), a.updatedAt()))
                .map(e -> Map.of("id", e.id(), "category", e.category(), "key", e.key(),
                        "value", e.value(), "scope", e.scope())).toList();
        for (var item : data) {
            bounded.add(item);
            if (MAPPER.writeValueAsString(bounded).length() > 12_000) {
                bounded.remove(bounded.size() - 1);
                break;
            }
        }
        return bounded.isEmpty() ? "" : MAPPER.writeValueAsString(bounded);
    }

    public synchronized void setEnabled(boolean enabled) throws IOException {
        persist(new Profile(enabled, profile.entries()));
    }

    public synchronized boolean forget(String id) throws IOException {
        List<ProfileEntry> next = new ArrayList<>(profile.entries());
        if (!next.removeIf(e -> e.id().equals(id))) {
            return false;
        }
        persist(new Profile(profile.enabled(), List.copyOf(next)));
        return true;
    }

    public synchronized void clear() throws IOException {
        persist(new Profile(profile.enabled(), List.of()));
    }

    /** 严格校验整批操作，任何无效项都不落盘。证据必须来自本轮用户原始消息。 */
    public synchronized List<String> apply(String json, String prompt, String session, String project)
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
        long now = System.currentTimeMillis();
        for (JsonNode op : root.path("operations")) {
            String action = text(op, "action", 10);
            String category = text(op, "category", 16);
            String key = text(op, "key", 64);
            String scope = text(op, "scope", 10);
            String evidence = text(op, "evidence", 300);
            if (!List.of("fact", "preference").contains(category)
                    || !key.matches("[a-z][a-z0-9_.-]{0,63}")
                    || !List.of("global", "project").contains(scope) || !prompt.contains(evidence)) {
                throw new IOException("记忆操作或用户原文证据无效");
            }
            String targetScope = scope.equals("global") ? "global" : project;
            ProfileEntry previous = next.stream().filter(e -> e.category().equals(category)
                    && e.key().equals(key) && e.scope().equals(targetScope)).findFirst().orElse(null);
            if (action.equals("delete")) {
                if (previous != null) {
                    next.remove(previous);
                    changes.add("已忘记 " + previous.key());
                }
            } else if (action.equals("set")) {
                String value = text(op, "value", 240);
                if (previous != null && previous.value().equals(value)) {
                    continue;
                }
                if (previous != null) {
                    next.remove(previous);
                }
                next.add(new ProfileEntry(previous == null ? UUID.randomUUID().toString() : previous.id(),
                        category, key, value, targetScope, session, evidence,
                        previous == null ? now : previous.createdAt(), now));
                changes.add((previous == null ? "已记住 " : "已更新 ") + key + "：" + value
                        + (scope.equals("project") ? "（当前项目）" : "（全局）"));
            } else {
                throw new IOException("未知记忆操作");
            }
        }
        if (!changes.isEmpty()) {
            validateEntries(next);
            persist(new Profile(profile.enabled(), List.copyOf(next)));
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
        if (data.length > 200_000) {
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
}
