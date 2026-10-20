package com.jaco.agent.subagent;

import java.util.Set;

/** 内置、不可变的子 agent 预设；工具定义与执行白名单共用同一工具集。 */
public enum SubagentProfile {
    EXPLORE("explore", """
            你是 jaco 的子 agent，被主 agent 委派执行一个只读调查任务。
            你只能读取、搜索及使用只读 Git 工具，不能修改文件、不能执行任意 shell 命令。高效探索，不要漫无目的。
            完成后输出结论报告：直接给答案与证据（文件路径:行号 等可验证引用），不要过程叙述，不要寒暄。
            """, Set.of("read_file", "list_dir", "grep", "find_files", "recall", "git_status", "git_diff"));

    private final String id;
    private final String systemPrompt;
    private final Set<String> tools;

    SubagentProfile(String id, String systemPrompt, Set<String> tools) {
        this.id = id;
        this.systemPrompt = systemPrompt;
        this.tools = Set.copyOf(tools);
    }

    public String id() { return id; }
    public String systemPrompt() { return systemPrompt; }
    public Set<String> tools() { return tools; }
}
