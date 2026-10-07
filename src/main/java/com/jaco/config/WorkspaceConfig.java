package com.jaco.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkspaceConfig(@JsonProperty("extra_roots") List<String> extraRoots) {
}
