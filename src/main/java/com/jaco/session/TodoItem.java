package com.jaco.session;

/** 稳定 ID 用于整体替换清单时保留任务身份。 */
public record TodoItem(String id, String content, Status status) {
    public enum Status { pending, in_progress, completed }
    public TodoItem {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("清单 ID 必须是 1～64 位字母、数字、下划线或连字符");
        if (content == null || content.isBlank() || content.length() > 500) throw new IllegalArgumentException("清单内容必须为 1～500 个字符且非空白");
        if (status == null) throw new IllegalArgumentException("必须提供清单状态");
    }
}
