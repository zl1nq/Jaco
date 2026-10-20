package com.jaco.agent.subagent;

import java.util.Set;
import java.util.TreeSet;

/** 内置、不可变的子 agent 预设；工具定义与执行白名单共用同一工具集。 */
public enum SubagentProfile {
    EXPLORE("explore", """
            你是 jaco 的子 agent，被主 agent 委派执行一个只读调查任务。
            你只能读取、搜索及使用只读 Git 工具，不能修改文件、不能执行任意 shell 命令。高效探索，不要漫无目的。
            完成后输出结论报告：直接给答案与证据（文件路径:行号 等可验证引用），不要过程叙述，不要寒暄。
            """, Set.of("read_file", "list_dir", "grep", "find_files", "recall", "git_status", "git_diff")),
    EDIT("edit", """
            你是 jaco 的代码修改子 agent，执行主 agent 明确委派的文件修改任务。
            只能使用本次工具范围中的读取、搜索、只读 Git、write_file 和 edit_file；不得执行 shell、联网或创建子 agent。
            修改已有文件前先 read_file，携带返回的 expected_version。优先 edit_file 做最小修改，新文件用 write_file。
            修改计划会显示预览并走宿主权限确认；启动任务的授权不代表写入授权。拒绝、取消或版本冲突时不得绕过。
            没有命令工具，不能声称已运行测试。完成后报告修改文件、具体变化、验证情况与未完成事项。
            """, Set.of("read_file", "list_dir", "grep", "find_files", "recall", "git_status", "git_diff", "write_file", "edit_file"));

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

    public static SubagentProfile fromId(String id) {
        for (var profile : values()) {
            if (profile.id.equals(id)) return profile;
        }
        throw new IllegalArgumentException("未知子 agent 预设: " + id);
    }

    /** 省略时使用完整预设；显式空集表示禁止工具。越界请求整体拒绝。 */
    public Set<String> selectTools(Set<String> requested) {
        if (requested == null) return tools;
        Set<String> selected = Set.copyOf(requested);
        var denied = new TreeSet<>(selected);
        denied.removeAll(tools);
        if (!denied.isEmpty()) throw new IllegalArgumentException("预设 " + id + " 不允许工具: " + denied);
        return selected;
    }
}
