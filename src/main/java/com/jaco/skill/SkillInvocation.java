package com.jaco.skill;

/** 活跃状态仅属于当前轮次，不能从历史归档自动恢复。 */
public record SkillInvocation(SkillDefinition definition, String source) {}
