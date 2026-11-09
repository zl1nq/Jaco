package com.jaco.skill;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** 先构建完整目录再发布；项目中的无效技能同样占据简称，不能悄悄回退。 */
public final class SkillRegistry {
    public record Entry(String id, SkillDefinition skill, String error) {}
    private final Path userRoot;
    private final Path projectRoot;
    private final SkillLoader loader = new SkillLoader();
    private volatile List<Entry> entries = List.of();

    public SkillRegistry(Path userRoot, Path projectRoot) {
        this.userRoot = userRoot;
        this.projectRoot = projectRoot;
        reload();
    }
    public static SkillRegistry empty() { return new SkillRegistry(null, null); }
    public synchronized void reload() {
        List<Entry> next = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        scan("project", projectRoot, next, seen);
        scan("user", userRoot, next, seen);
        entries = List.copyOf(next);
    }
    private void scan(String source, Path root, List<Entry> result, Set<Path> seen) {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.list(root)) {
            for (Path directory : paths.sorted(Comparator.comparing(p -> p.getFileName().toString())).toList()) {
                if (!Files.isDirectory(directory)) continue;
                String name = directory.getFileName().toString();
                if (!Files.exists(directory.resolve("SKILL.md"), LinkOption.NOFOLLOW_LINKS)) continue;
                try {
                    SkillDefinition definition = loader.load(source, name, root, directory);
                    if (!seen.add(definition.file())) throw new IOException("重复真实路径，已由更高优先级目录收录");
                    result.add(new Entry(source + ":" + name, definition, null));
                } catch (IOException | RuntimeException e) {
                    result.add(new Entry(source + ":" + name, null, e.getMessage()));
                }
            }
        } catch (IOException | RuntimeException e) {
            result.add(new Entry(source + ":<目录>", null, "技能目录读取失败: " + e.getMessage()));
        }
    }
    public List<Entry> entries() { return entries; }
    public SkillDefinition resolve(String name) {
        List<Entry> snapshot = entries;
        Entry found = snapshot.stream().filter(e -> e.id().equals(name.contains(":") ? name : "project:" + name)).findFirst().orElse(null);
        if (found == null && !name.contains(":"))
            found = snapshot.stream().filter(e -> e.id().equals("user:" + name)).findFirst().orElse(null);
        if (found == null) throw new IllegalArgumentException("没有找到技能: " + name);
        if (found.skill() == null) throw new IllegalArgumentException(found.id() + ": " + found.error());
        return found.skill();
    }
    public SkillDefinition fresh(SkillDefinition definition) throws IOException {
        String source = definition.id().substring(0, definition.id().indexOf(':'));
        Path sourceRoot = source.equals("project") ? projectRoot : userRoot;
        SkillDefinition fresh = loader.load(source, definition.name(), sourceRoot, sourceRoot.resolve(definition.name()));
        if (!fresh.file().equals(definition.file()) || !fresh.version().equals(definition.version()))
            throw new IOException("技能已改变，请在后续轮次执行 /skills reload 后重新加载: " + definition.id());
        return fresh;
    }
    public String describe() {
        if (entries.isEmpty()) return "（暂无技能）";
        StringBuilder result = new StringBuilder("技能目录：");
        Set<String> projectNames = new HashSet<>();
        for (Entry entry : entries) {
            String name = entry.id().substring(entry.id().indexOf(':') + 1);
            if (entry.id().startsWith("project:")) projectNames.add(name);
            result.append("\n").append(entry.id());
            if (entry.id().startsWith("user:") && projectNames.contains(name)) result.append("（简称被项目技能覆盖）");
            if (entry.skill() == null) result.append(" [不可用] ").append(entry.error());
            else result.append(" — ").append(entry.skill().description())
                    .append(entry.skill().userInvocable() ? "" : " [禁止用户调用]")
                    .append(entry.skill().disableModelInvocation() ? " [禁止模型自主调用]" : "");
        }
        return result.toString();
    }
    public String show(String name) throws IOException {
        SkillDefinition skill = fresh(resolve(name));
        return skill.id() + "\n来源: " + skill.file() + "\n" + skill.description()
                + "\n参数提示: " + skill.argumentHint() + "\n\n" + skill.body();
    }
}
