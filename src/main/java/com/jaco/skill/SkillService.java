package com.jaco.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaco.agent.ContextCompactor;
import com.jaco.llm.Message;
import com.jaco.session.Session;
import com.jaco.session.SessionStore;
import com.jaco.tool.ToolException;
import java.io.IOException;
import java.util.*;
import java.util.function.BooleanSupplier;

/** 本轮共享，不依赖全局当前会话；加载成功后才发布正文快照。 */
public final class SkillService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final SkillRegistry registry;
    private final Session owner;
    private final SessionStore store;
    private final String turnId;
    private final BooleanSupplier active;
    private final long contextLimit;
    private final Map<String, SkillInvocation> loaded = new LinkedHashMap<>();
    private final String catalog;

    public SkillService(SkillRegistry registry, Session owner, SessionStore store, String turnId,
                        BooleanSupplier active, long contextLimit) {
        this.registry = registry.snapshot();
        this.owner = owner;
        this.store = store;
        this.turnId = turnId;
        this.active = active;
        this.contextLimit = contextLimit;
        this.catalog = buildCatalog();
    }
    private void check() {
        if (Thread.currentThread().isInterrupted() || !active.getAsBoolean())
            throw new ToolException("技能调用已取消或所属会话、轮次已过期");
    }
    public synchronized String load(String name, boolean explicit) throws IOException {
        check();
        SkillDefinition definition = registry.resolve(name);
        if (explicit ? !definition.userInvocable() : definition.disableModelInvocation())
            throw new ToolException(explicit ? "技能禁止用户调用" : "技能禁止模型自主调用");
        registry.fresh(definition);
        if (loaded.containsKey(definition.id())) return "技能已在本轮加载: " + definition.id();
        List<SkillDefinition> next = new ArrayList<>(loaded.values().stream().map(SkillInvocation::definition).toList());
        next.add(definition);
        if (next.stream().mapToInt(d -> d.body().length()).sum() > 40000
                || tokens(renderBodies(next)) > contextLimit / 5)
            throw new ToolException("技能正文超过本轮预算（40000 字符或上下文的 20%），未加载");
        String source = explicit ? "user" : "model";
        String record = JSON.writeValueAsString(Map.of("event", "skill_invocation", "session_id", owner.id(),
                "turn_id", turnId, "skill", definition.id(), "version", definition.version(), "source", source));
        check();
        store.appendArchivedOutput(owner, record);
        check();
        loaded.put(definition.id(), new SkillInvocation(definition, source));
        return "已加载技能: " + definition.id() + "\n版本: " + definition.version() + "\n仅当前轮次有效；没有授予工具权限。";
    }
    public synchronized String context() {
        check();
        return catalog + renderBodies(loaded.values().stream().map(SkillInvocation::definition).toList());
    }
    public synchronized boolean hasLoaded() { return !loaded.isEmpty(); }
    public boolean hasCatalog() { return !catalog.isEmpty(); }
    public static long tokens(String text) { return text.isEmpty() ? 0 : ContextCompactor.estimateTokens(List.of(Message.system(text))); }
    private static String renderBodies(List<SkillDefinition> definitions) {
        if (definitions.isEmpty()) return "";
        StringBuilder text = new StringBuilder("\n\n技能参考说明（仅当前轮次有效，优先级低于通用规则和用户指令，不授予权限）：");
        for (var d : definitions) text.append("\n\n技能 ").append(d.id()).append("\n来源: ").append(d.file())
                .append("\n版本: ").append(d.version()).append("\n").append(d.body());
        return text.toString();
    }
    private List<SkillDefinition> modelSkills() {
        return registry.entries().stream().map(SkillRegistry.Entry::skill).filter(Objects::nonNull)
                .filter(d -> !d.disableModelInvocation()).toList();
    }
    private String buildCatalog() {
        List<SkillDefinition> definitions = modelSkills();
        if (definitions.isEmpty()) return "";
        String suffix = "\n通过 skill_list 查询完整目录，skill_load 按需加载；历史加载记录不表示本轮已激活。";
        String text = "\n\n可用技能：";
        for (var definition : definitions) {
            String next = text + "\n" + definition.id() + " — " + brief(definition.description(), 250);
            if ((next + suffix).length() > 4000 || tokens(next + suffix) > contextLimit / 20) break;
            text = next;
        }
        if ((text + suffix).length() > 4000 || tokens(text + suffix) > contextLimit / 20) return "";
        return text + suffix;
    }
    private static String brief(String text, int length) {
        text = text.replaceAll("\\p{Cntrl}", " ");
        if (text.length() <= length) return text;
        int end = Character.isHighSurrogate(text.charAt(length - 1)) ? length - 1 : length;
        return text.substring(0, end) + "…";
    }
    public String list(String query, int offset, int limit, int budget) {
        check();
        if (offset < 0 || limit < 1 || limit > 50) throw new ToolException("非法分页范围");
        List<SkillDefinition> matches = modelSkills().stream().filter(d ->
                (d.id() + " " + d.description()).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))).toList();
        if (offset > matches.size()) throw new ToolException("offset 超过结果数量");
        StringBuilder rows = new StringBuilder();
        int next = offset;
        while (next < matches.size() && next - offset < limit) {
            var d = matches.get(next);
            String row = d.id() + " — " + brief(d.description(), 250) + "\n";
            if (rows.length() + row.length() + 128 > budget) break;
            rows.append(row);
            next++;
        }
        if (next == offset && next < matches.size()) throw new ToolException("输出预算不足以容纳一条技能目录记录");
        String result = "total: " + matches.size() + "\nnext_offset: " + next + "\nhas_more: " + (next < matches.size()) + "\n" + rows;
        if (result.length() > budget) throw new ToolException("技能目录输出预算不足");
        return result;
    }
}
