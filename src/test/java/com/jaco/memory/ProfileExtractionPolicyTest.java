package com.jaco.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.jaco.memory.ProfileExtractionPolicy.Action.*;
import static org.junit.jupiter.api.Assertions.*;

class ProfileExtractionPolicyTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "这个参数默认是多少？", "把接口超时默认值改成 30 秒", "默认值为30秒", "这是一直存在的默认配置", "以后再构建",
            "以后这个接口默认回复空字符串", "测试默认返回值", "我忘记密码了", "忘记密码怎么办？", "记住了吗？",
            "你记住我的名字了吗？", "我的职业是什么？", "我是一名医生吗？", "我是来问问题的", "我是说这个方法有问题",
            "我是想确认默认配置", "我叫你改代码", "叫我帮你修改代码", "How do I set a default value?",
            "By default, this API will reply with null", "I am asking about the default configuration",
            "默认值包含中文", "默认返回中文字符串", "默认使用中文编码", "以后使用详细日志",
            "我是不是摄影师", "我的职业是啥", "我喜欢什么摄影风格", "我习惯怎么回答"
    })
    void technicalDiscussionQuestionsAndOperationalStatementsUseBatch(String prompt) {
        assertEquals(BATCH, ProfileExtractionPolicy.classify(prompt).action(), prompt);
        assertTrue(ProfileExtractionPolicy.shouldQueue(prompt));
        assertFalse(ProfileExtractionPolicy.immediate(prompt));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "记住我的称呼是小林。", "请记住：以后用中文回答", "忘记我的职业", "请忘掉我是摄影师这件事",
            "默认用中文回答我", "以后回复尽量简短", "以后能不能都用中文回复？", "以后不要自动打包",
            "请你以后修改代码时先运行测试", "这个项目以后统一用 Maven", "当前仓库今后一律使用英文提交信息",
            "我是一名摄影师", "我从事园艺工作", "我的职业是调香师", "我是摄影师", "我是 Java 开发者",
            "我是一位陶艺师", "我叫小林", "以后称呼我‘小林’", "叫我小张", "我喜欢摄影", "我习惯简短回答",
            "我不是工程师", "更正一下，我现在做产品设计", "以后别叫我张总，叫我小张", "I prefer English",
            "Please remember my name is Alex", "I am a photographer", "From now on, reply in English", "Always answer briefly"
    })
    void explicitMemoryPersonalInformationAndLongTermPreferencesTriggerImmediately(String prompt) {
        assertEquals(IMMEDIATE, ProfileExtractionPolicy.classify(prompt).action(), prompt);
        assertTrue(ProfileExtractionPolicy.shouldQueue(prompt));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "下面这段测试数据里有一句：“我是一名医生。”", "他说：“以后默认用中文回答。”", "客户说：\"我是摄影师\"",
            "> 我是一名摄影师\n> 记住我的称呼是小林", "下面是示例：\n```text\n我是摄影师\n默认用中文回复\n```",
            "~~~text\n记住我的称呼是小林\n~~~", "```text\n我是摄影师", "配置描述是 `默认用中文回复`",
            "四个反引号：\n````text\n```\n记住我的名字是小林\n````", "“我是一名摄影师。”",
            "`\n我是摄影师\n`", "    我是一名摄影师", "\t默认用中文回复"
    })
    void codeAndExplicitQuotationsDoNotTriggerButRemainAvailableForBatch(String prompt) {
        assertEquals(BATCH, ProfileExtractionPolicy.classify(prompt).action(), prompt);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "继续", "好的", "OK!", "谢谢"})
    void onlyBlankAndPureConfirmationAreSkipped(String prompt) {
        assertEquals(SKIP, ProfileExtractionPolicy.classify(prompt).action());
        assertFalse(ProfileExtractionPolicy.shouldQueue(prompt));
    }

    @Test void preservesQuotedNicknameAndProseOutsideCodeAndReferenceBlocks() {
        assertEquals(IMMEDIATE, ProfileExtractionPolicy.classify("以后称呼我“小林”").action());
        assertEquals(IMMEDIATE, ProfileExtractionPolicy.classify(
                "```text\n我是医生\n```\n我是一名摄影师").action());
        assertEquals(IMMEDIATE, ProfileExtractionPolicy.classify(
                "> 我是一名医生\n请记住我的称呼是小林").action());
        assertEquals(IMMEDIATE, ProfileExtractionPolicy.classify(
                "他说：“我是医生。”\n我从事园艺工作").action());
    }

    @Test void decisionReasonsExplainLocalRoutingWithoutModelInvocation() {
        assertEquals("explicit_memory_request", ProfileExtractionPolicy.classify("忘记我的职业").reason());
        assertEquals("self_introduction", ProfileExtractionPolicy.classify("我是一名摄影师").reason());
        assertEquals("response_preference", ProfileExtractionPolicy.classify("默认用中文回复").reason());
        assertEquals("project_convention", ProfileExtractionPolicy.classify("这个项目以后统一用 Maven").reason());
        assertEquals("personal_correction", ProfileExtractionPolicy.classify("更正一下，我现在做产品设计").reason());
        assertEquals("no_explicit_intent", ProfileExtractionPolicy.classify("默认值是30秒").reason());
    }
}
