package com.jaco.config;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record WorkspaceConfig(@JsonProperty("extra_roots") List<String> extraRoots) {
}
