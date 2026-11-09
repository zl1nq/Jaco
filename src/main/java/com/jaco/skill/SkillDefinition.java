package com.jaco.skill;

import java.nio.file.Path;

/** 已校验的目录快照；正文在调用时重新读取并核对版本。 */
public record SkillDefinition(String id, String name, String description, String argumentHint,
                              boolean userInvocable, boolean disableModelInvocation,
                              Path root, Path file, String body, String version) {}
