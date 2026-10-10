package com.jaco.memory;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 两张本地规则表：任务触发词与画像键路由。选择过程不调用模型。 */
public final class ProfileContextSelector {
    public static final int MAX_CONTEXT_CHARS = 2000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public enum Topic { CODING, JAVA, JAVASCRIPT, TESTING, GIT_COMMIT, WRITING, PERSONAL }

    // 只列明确的通用键；职业、技术背景及任意 response.* 都不会自动常驻。
    private static final Set<String> RESIDENT_PREFERENCES = Set.of(
            "response.language", "response.length", "response.tone");
    private static final Map<String, String> ALIASES = Map.of(
            "reply.language", "response.language", "reply.length", "response.length",
            "reply.tone", "response.tone", "identity.name", "identity.preferred_name",
            "identity.job", "identity.occupation", "identity.familiar_technologies", "identity.familiar_tech");

    private static final Map<Topic, Pattern> TRIGGERS = Map.of(
            Topic.CODING, pattern("修改代码|改代码|实现功能|编写代码|重构|修复.{0,8}(代码|方法|函数|bug)|" + words("implement|refactor|coding|debug")),
            Topic.JAVA, pattern(words("java|jdk|maven|spring|junit")),
            Topic.JAVASCRIPT, pattern(words("javascript|typescript|node(?:\\.js)?|npm|react|vue")),
            Topic.TESTING, pattern("单元测试|回归测试|运行测试|补.{0,4}测试|测试用例|" + words("unit\\s+tests?|tests?|testing|junit|pytest|jest")),
            Topic.GIT_COMMIT, pattern("提交代码|代码提交|提交信息|创建提交|提交改动|提交修改|提交一下|提交这些|提交格式|"
                    + "^(?:请|帮我|现在|直接|先)?提交(?:吧|一下)?[\\s。！!]*$|" + words("git\\s+commit|commit(?:\\s+message)?")),
            Topic.WRITING, pattern("(?:写|编写|修改|整理|生成).{0,6}(?:文档|说明)|文档格式|" + words("documentation|readme|docs?")),
            Topic.PERSONAL, pattern("我的(职业|背景|信息|偏好|画像|名字|称呼)|我(熟悉|擅长)什么|你.{0,6}记得.{0,4}我|"
                    + words("my\\s+(background|preferences?|profile|name)")));

    private static final Map<String, Set<Topic>> EXACT_ROUTES = Map.of(
            "git.commit_style", Set.of(Topic.GIT_COMMIT),
            "git.commit_behavior", Set.of(Topic.GIT_COMMIT),
            "identity.occupation", Set.of(Topic.PERSONAL),
            "identity.familiar_tech", Set.of(Topic.CODING));
    private static final Map<String, Set<Topic>> PREFIX_ROUTES = Map.of(
            "git.", Set.of(Topic.GIT_COMMIT),
            "code.java.", Set.of(Topic.JAVA),
            "code.javascript.", Set.of(Topic.JAVASCRIPT),
            "code.typescript.", Set.of(Topic.JAVASCRIPT),
            "code.testing.", Set.of(Topic.TESTING, Topic.CODING),
            "code.", Set.of(Topic.CODING),
            "document.", Set.of(Topic.WRITING));
    private static final Pattern CONTINUATION = pattern(
            "^(?:(?:好的|好)[，,\\s]*)?(?:就|先|请|现在)*(?:继续(?:吧|做|执行)?|接着(?:做|来)?|"
                    + "按(?:照)?(?:刚才|之前|上面|上述|这个|那个)的?(?:方案|计划|步骤)(?:做|执行|继续|修改|实现)?|"
                    + "continue|go\\s+on|proceed)[\\s。！!,.，]*$");

    public static String canonicalKey(String key) {
        return ALIASES.getOrDefault(key, key);
    }

    public static boolean isContinuation(String prompt) {
        return CONTINUATION.matcher(prompt.strip()).matches();
    }

    public static Set<Topic> topics(String prompt, Set<Topic> previous) {
        if (isContinuation(prompt)) {
            return Set.copyOf(previous);
        }
        EnumSet<Topic> result = EnumSet.noneOf(Topic.class);
        TRIGGERS.forEach((topic, trigger) -> {
            if (trigger.matcher(prompt).find()) result.add(topic);
        });
        if (result.contains(Topic.JAVA) || result.contains(Topic.JAVASCRIPT) || result.contains(Topic.TESTING)) {
            result.add(Topic.CODING);
        }
        return Set.copyOf(result);
    }

    /** 先解决作用范围与旧键别名，再按相关性选条目；不发送 ID、路径、证据等元数据。 */
    public static String select(List<ProfileEntry> entries, String project, Set<Topic> topics) throws IOException {
        Map<String, ProfileEntry> resolved = new LinkedHashMap<>();
        for (String scope : List.of("global", project)) {
            entries.stream().filter(e -> e.scope().equals(scope))
                    .sorted(Comparator.comparingLong(ProfileEntry::updatedAt).thenComparing(ProfileEntry::id))
                    .forEach(e -> resolved.put(e.category() + ":" + canonicalKey(e.key()), e));
        }
        List<ProfileEntry> selected = new ArrayList<>(resolved.values());
        selected.removeIf(e -> relevance(e, topics) == 0);
        selected.sort(Comparator.<ProfileEntry>comparingInt(e -> relevance(e, topics)).reversed()
                .thenComparing(Comparator.comparingLong(ProfileEntry::updatedAt).reversed())
                .thenComparing(e -> canonicalKey(e.key())));
        StringBuilder context = new StringBuilder();
        for (ProfileEntry entry : selected) {
            String line = canonicalKey(entry.key()) + ": " + MAPPER.writeValueAsString(entry.value()) + "\n";
            if (context.length() + line.length() <= MAX_CONTEXT_CHARS) {
                context.append(line);
            }
        }
        return context.toString().stripTrailing();
    }

    private static int relevance(ProfileEntry entry, Set<Topic> topics) {
        String key = canonicalKey(entry.key());
        if ((entry.category().equals("preference") && RESIDENT_PREFERENCES.contains(key))
                || (entry.category().equals("fact") && key.equals("identity.preferred_name"))) {
            return 1000;
        }
        Set<Topic> route = EXACT_ROUTES.get(key);
        int score = key.equals("identity.familiar_tech") ? 100 : 300;
        if (route == null) {
            String prefix = PREFIX_ROUTES.keySet().stream().filter(key::startsWith)
                    .max(Comparator.comparingInt(String::length)).orElse(null);
            route = prefix == null ? Set.of() : PREFIX_ROUTES.get(prefix);
            score = "code.".equals(prefix) ? 100 : 200;
        }
        for (Topic topic : route) {
            if (topics.contains(topic)) return score;
        }
        // 明确询问画像时可以加载未映射条目；普通任务不使用猜测路由。
        return topics.contains(Topic.PERSONAL) ? 150 : 0;
    }

    private static Pattern pattern(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /** 英文标识符边界：允许“修改Java代码”这类中文紧邻写法，拒绝 JavaScript 命中 Java。 */
    private static String words(String alternatives) {
        return "(?<![a-z0-9_])(?:" + alternatives + ")(?![a-z0-9_])";
    }

    private ProfileContextSelector() {
    }
}
