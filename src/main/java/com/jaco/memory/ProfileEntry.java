package com.jaco.memory;

/** 用户明确表达的事实或偏好；scope 为 global 或工作目录的规范路径。 */
public record ProfileEntry(String id, String category, String key, String value, String scope,
                           String sourceSession, String evidence, long createdAt, long updatedAt) {
}
