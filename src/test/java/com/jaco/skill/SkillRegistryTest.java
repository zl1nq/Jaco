package com.jaco.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class SkillRegistryTest {
    @TempDir Path dir;
    private Path write(Path root, String name, String metadata, String body) throws Exception {
        Path file = root.resolve(name).resolve("SKILL.md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "---\nname: " + name + "\ndescription: Review Java\n" + metadata + "---\n" + body);
        return file;
    }
    @Test void overridesAreStableAndInvalidProjectDoesNotFallBack() throws Exception {
        Path user = dir.resolve("user"), project = dir.resolve("project");
        write(user, "review", "", "User");
        write(project, "review", "", "Project");
        write(project, "aaa", "", "First");
        var registry = new SkillRegistry(user, project);
        assertEquals("project:aaa", registry.entries().getFirst().id());
        assertEquals("Project", registry.resolve("review").body());
        assertEquals("User", registry.resolve("user:review").body());
        assertTrue(registry.describe().contains("覆盖"));
        write(project, "review", "allowed-tools: run_command\n", "bad");
        registry.reload();
        assertThrows(IllegalArgumentException.class, () -> registry.resolve("review"));
        assertEquals("User", registry.resolve("user:review").body());
        assertTrue(registry.show("aaa").contains("First"));
    }
    @Test void rejectsMalformedMetadataAndOversizedContentWithoutLosingOtherSkills() throws Exception {
        String[] invalid = {"name: duplicate\n", "user-invocable: 'true'\n", "description: [bad]\n", "hooks: {}\n", "argument-hint: 123\n"};
        for (String metadata : invalid) {
            write(dir, "bad", metadata, "Body");
            write(dir, "good", "", "Good");
            var registry = new SkillRegistry(null, dir);
            assertThrows(IllegalArgumentException.class, () -> registry.resolve("bad"));
            assertEquals("Good", registry.resolve("good").body());
        }
        write(dir, "bad", "", " ");
        assertThrows(IllegalArgumentException.class, () -> new SkillRegistry(null, dir).resolve("bad"));
        write(dir, "bad", "", "x".repeat(20001));
        assertThrows(IllegalArgumentException.class, () -> new SkillRegistry(null, dir).resolve("bad"));
        write(dir, "bad", "argument-hint: " + "x".repeat(128 * 1024) + "\n", "body");
        assertThrows(IllegalArgumentException.class, () -> new SkillRegistry(null, dir).resolve("bad"));
        Path nested = dir.resolve("nested/another");
        write(nested, "hidden", "", "Hidden");
        assertThrows(IllegalArgumentException.class, () -> new SkillRegistry(null, dir).resolve("hidden"));
    }
    @Test void versionChangesRequireReloadAndSharedRootsAreDeduplicated() throws Exception {
        Path file = write(dir, "review", "", "Before");
        var registry = new SkillRegistry(dir, dir);
        assertEquals(1, registry.entries().stream().filter(e -> e.skill() != null).count());
        assertThrows(IllegalArgumentException.class, () -> registry.resolve("user:review"));
        Files.writeString(file, Files.readString(file).replace("Before", "After"));
        assertThrows(java.io.IOException.class, () -> registry.show("review"));
        registry.reload();
        assertTrue(registry.show("review").contains("After"));
    }
    @Test void rejectsSymlinkEscapeWhenPlatformAllowsLinks() throws Exception {
        Path root = dir.resolve("skills"), outside = dir.resolve("outside");
        Files.createDirectories(root);
        write(outside, "escape", "", "Outside");
        try { Files.createSymbolicLink(root.resolve("escape"), outside.resolve("escape")); }
        catch (java.io.IOException | UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.abort("平台不支持创建符号链接");
        }
        assertThrows(IllegalArgumentException.class, () -> new SkillRegistry(null, root).resolve("escape"));
    }
}
