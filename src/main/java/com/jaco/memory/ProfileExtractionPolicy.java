package com.jaco.memory;

import java.util.regex.Pattern;

/** 本地筛选只决定提取时机，长期信息的语义判断仍交给模型。 */
public final class ProfileExtractionPolicy {
    private static final Pattern CONFIRMATION = Pattern.compile(
            "^(?:继续(?:吧|做|执行)?|好的?|嗯|行|收到|明白了?|谢谢|ok(?:ay)?|yes|thanks?|continue)[\\s。！!,.，]*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern IMMEDIATE = Pattern.compile(
            "记住|记一下|忘记|以后|默认|一直|我喜欢|我偏好|我习惯|我叫|叫我|我的名字|我的职业|"
                    + "我是.{0,30}(?:工程师|开发者|程序员|教师|老师|学生|医生|设计师|律师|研究员)|"
                    + "(?<![a-z])(?:remember|forget|i\\s+(?:am|prefer|like)|my\\s+name|from\\s+now\\s+on)(?![a-z])",
            Pattern.CASE_INSENSITIVE);

    public static boolean shouldQueue(String prompt) {
        return !prompt.isBlank() && !CONFIRMATION.matcher(prompt.strip()).matches();
    }

    public static boolean immediate(String prompt) {
        return IMMEDIATE.matcher(prompt).find();
    }

    private ProfileExtractionPolicy() {
    }
}
