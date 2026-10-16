package com.jaco.memory;

/** 未提取的用户原始消息；助手文本只用于理解指代，不能作为事实证据。 */
public record PendingProfileMessage(String id, String session, String project,
                                    String message, String previousAssistant, long createdAt) {
}
