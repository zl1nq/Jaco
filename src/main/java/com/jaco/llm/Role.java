package com.jaco.llm;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum Role {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool");

    private final String wire;

    Role(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static Role from(String value) {
        for (Role r : values()) {
            if (r.wire.equalsIgnoreCase(value)) {
                return r;
            }
        }
        throw new IllegalArgumentException("未知 role: " + value);
    }
}
