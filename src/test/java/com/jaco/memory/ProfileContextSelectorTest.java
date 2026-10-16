package com.jaco.memory;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.jaco.memory.ProfileContextSelector.Topic.*;
import static org.junit.jupiter.api.Assertions.*;

class ProfileContextSelectorTest {
    private static final String PROJECT = "project-a";
    private static ProfileEntry entry(String category, String key, String value, String scope, long updated) {
        return new ProfileEntry(key + updated, category, key, value, scope, "source", "evidence", 1, updated);
    }
    private static ProfileEntry preference(String key, String value) {
        return entry("preference", key, value, "global", 1);
    }
    private static String context(List<ProfileEntry> entries, String prompt) throws Exception {
        return ProfileContextSelector.select(entries, PROJECT, ProfileContextSelector.topics(prompt, Set.of()));
    }

    @Test void ordinaryQuestionLoadsOnlyExplicitResidentKeys() throws Exception {
        String text = context(List.of(preference("response.language", "中文"),
                preference("response.length", "简洁"), preference("response.tone", "直接"),
                entry("fact", "identity.preferred_name", "小林", "global", 1),
                entry("fact", "identity.occupation", "工程师", "global", 100),
                entry("fact", "identity.familiar_tech", "Java", "global", 100),
                preference("git.commit_style", "中文提交"), preference("response.other", "其他偏好")), "你好");
        assertTrue(text.contains("中文"));
        assertTrue(text.contains("简洁"));
        assertTrue(text.contains("直接"));
        assertTrue(text.contains("小林"));
        assertFalse(text.contains("工程师"));
        assertFalse(text.contains("Java"));
        assertFalse(text.contains("中文提交"));
        assertFalse(text.contains("其他偏好"));
    }

    @Test void commitTaskLoadsCommitPreferencesButNotCodeOrDocumentPreferences() throws Exception {
        String text = context(List.of(preference("git.commit_style", "英文前缀中文主体"),
                preference("git.commit_behavior", "验证后提交"), preference("code.java.style", "Java风格"),
                preference("document.format", "文档风格")), "现在提交代码");
        assertTrue(text.contains("英文前缀中文主体"));
        assertTrue(text.contains("验证后提交"));
        assertFalse(text.contains("Java风格"));
        assertFalse(text.contains("文档风格"));
        assertTrue(ProfileContextSelector.topics("Git COMMIT message", Set.of()).contains(GIT_COMMIT));
        assertTrue(ProfileContextSelector.topics("提交", Set.of()).contains(GIT_COMMIT));
        assertFalse(ProfileContextSelector.topics("提交表单", Set.of()).contains(GIT_COMMIT));
    }

    @Test void javaAndJavaScriptRemainDistinctIncludingChineseAdjacentIdentifiers() throws Exception {
        List<ProfileEntry> entries = List.of(preference("code.java.style", "Java专属"),
                preference("code.javascript.style", "JS专属"), preference("code.style", "通用风格"),
                entry("fact", "identity.familiar_tech", "熟悉的技术", "global", 1));
        String java = context(entries, "修改Java代码并补单元测试");
        assertTrue(java.contains("Java专属"));
        assertFalse(java.contains("JS专属"));
        assertTrue(java.contains("通用风格"));
        assertTrue(java.contains("熟悉的技术"));
        String js = context(entries, "修改JavaScript代码");
        assertFalse(js.contains("Java专属"));
        assertTrue(js.contains("JS专属"));
        assertTrue(js.contains("通用风格"));
        assertFalse(ProfileContextSelector.topics("JavaScript myjava java_style", Set.of()).contains(JAVA));
    }

    @Test void writingAndTestingCanMatchTogether() throws Exception {
        var topics = ProfileContextSelector.topics("运行测试并修改 README", Set.of());
        assertTrue(topics.containsAll(Set.of(TESTING, CODING, WRITING)));
        String text = context(List.of(preference("code.testing.framework", "测试习惯"),
                preference("document.format", "文档习惯"), preference("git.commit_style", "提交习惯")),
                "运行测试并修改 README");
        assertTrue(text.contains("测试习惯"));
        assertTrue(text.contains("文档习惯"));
        assertFalse(text.contains("提交习惯"));
    }

    @Test void continuationInheritsButExplicitNewTaskAndGreetingResetPreviousTopics() {
        Set<ProfileContextSelector.Topic> previous = Set.of(JAVA, CODING);
        for (String input : List.of("继续", "好的，继续", "按刚才的方案做", "就先按照这个方案修改", "continue")) {
            assertEquals(previous, ProfileContextSelector.topics(input, previous), input);
        }
        assertEquals(Set.of(WRITING), ProfileContextSelector.topics("继续写文档", previous));
        assertEquals(Set.of(), ProfileContextSelector.topics("推荐一本小说", previous));
        assertEquals(Set.of(), ProfileContextSelector.topics("你好", previous));
    }

    @Test void projectOverrideHappensBeforeSelectionAndAliasesResolveWithoutSendingMetadata() throws Exception {
        List<ProfileEntry> entries = List.of(preference("response.language", "中文"),
                entry("preference", "reply.language", "英文", PROJECT, 1),
                entry("preference", "response.language", "法文", "project-b", 99));
        String text = context(entries, "你好");
        assertEquals("response.language: \"英文\"", text);
        assertFalse(text.contains("project-a"));
        assertFalse(text.contains("id"));
        assertFalse(text.contains("evidence"));
    }

    @Test void unknownKeysOnlyLoadForExplicitPersonalQuestion() throws Exception {
        List<ProfileEntry> entries = List.of(entry("fact", "identity.occupation", "工程师", "global", 1),
                preference("custom.preference", "未知偏好"));
        assertEquals("", context(entries, "解释一下递归"));
        String personal = context(entries, "你记得我的职业和偏好吗");
        assertTrue(personal.contains("工程师"));
        assertTrue(personal.contains("未知偏好"));
    }

    @Test void budgetPrioritizesResidentAndSpecificTaskPreferencesOverNewerGenericPreferences() throws Exception {
        List<ProfileEntry> entries = new ArrayList<>();
        entries.add(preference("response.language", "常驻语言"));
        entries.add(preference("code.java.style", "Java专属"));
        for (int i = 0; i < 40; i++) {
            entries.add(entry("preference", "code.generic" + i, "长".repeat(240), "global", 100 + i));
        }
        String text = context(entries, "修改Java代码");
        assertTrue(text.length() <= 2000);
        assertTrue(text.startsWith("response.language:"));
        assertTrue(text.contains("Java专属"));
        assertFalse(text.contains("code.generic0:"));
    }

    @Test void quotedValuesCannotBreakIntoMultipleLinesOrHideFollowingKeys() throws Exception {
        String text = context(List.of(preference("response.language", "中文\"其他内容")), "你好");
        assertEquals("response.language: \"中文\\\"其他内容\"", text);
    }
}
