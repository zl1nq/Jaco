package com.jaco.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaco.tool.builtin.WriteFileTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ToolSandboxTest {

    @TempDir
    Path tmp;

    private Path workspace() throws IOException {
        return Files.createDirectory(tmp.resolve("workspace")).toRealPath();
    }

    @Test
    void preservesAllMissingDirectories() throws Exception {
        Path root = workspace();
        ToolSandbox sandbox = new ToolSandbox(root, List.of());
        assertEquals(root.resolve("a/b/c.txt"), sandbox.resolve("a/b/c.txt"));
        assertEquals(root.resolve("a/b/c.txt"), sandbox.resolve(root.resolve("a/b/c.txt").toString()));
    }

    @Test
    void preservesPathBelowExistingAncestor() throws Exception {
        Path root = workspace();
        Files.createDirectory(root.resolve("a"));
        assertEquals(root.resolve("a/b/c.txt"), new ToolSandbox(root, List.of()).resolve("a/b/c.txt"));
    }

    @Test
    void resolvesExistingFileAndNormalizesDotSegments() throws Exception {
        Path root = workspace();
        Files.writeString(root.resolve("existing.txt"), "hello");
        ToolSandbox sandbox = new ToolSandbox(root, List.of());
        assertEquals(root.resolve("existing.txt"), sandbox.resolve("existing.txt"));
        assertEquals(root.resolve("a/c.txt"), sandbox.resolve("./a/b/../c.txt"));
    }

    @Test
    void rejectsTraversalAndSiblingWithSimilarName() throws Exception {
        Path root = workspace();
        ToolSandbox sandbox = new ToolSandbox(root, List.of());
        assertThrows(ToolException.class, () -> sandbox.resolve("../outside/a/b.txt"));
        assertThrows(ToolException.class, () -> sandbox.resolve(
                tmp.resolve("workspace-other/a/b.txt").toString()));
    }

    @Test
    void allowsNestedNewFilesUnderExtraRoot() throws Exception {
        Path root = workspace();
        Path extra = Files.createDirectory(tmp.resolve("extra")).toRealPath();
        ToolSandbox sandbox = new ToolSandbox(root, List.of(extra.toString()));
        assertEquals(extra.resolve("a/b/c.txt"), sandbox.resolve(extra.resolve("a/b/c.txt").toString()));
    }

    @Test
    void writeFileCreatesRequestedHierarchyWithoutOverwritingAncestorFile() throws Exception {
        Path root = workspace();
        Files.writeString(root.resolve("c.txt"), "keep");
        ToolSandbox sandbox = new ToolSandbox(root, List.of());
        var args = new ObjectMapper().createObjectNode()
                .put("path", "a/b/c.txt").put("content", "new content");
        new WriteFileTool().execute(args,
                new ToolContext(root, sandbox, "auto", new AtomicReference<>(), () -> false, null));

        assertEquals("new content", Files.readString(root.resolve("a/b/c.txt")));
        assertEquals("keep", Files.readString(root.resolve("c.txt")));
    }

    private void symlink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            assumeTrue(false, "当前环境不支持创建符号链接: " + e.getMessage());
        }
    }

    @Test
    void rejectsNewFilesUnderSymlinkOutsideWorkspace() throws Exception {
        Path root = workspace();
        Path outside = Files.createDirectory(tmp.resolve("outside"));
        symlink(root.resolve("link"), outside);
        ToolSandbox sandbox = new ToolSandbox(root, List.of());
        assertThrows(ToolException.class, () -> sandbox.resolve("link/a/b/c.txt"));
        assertFalse(Files.exists(outside.resolve("a")));
    }

    @Test
    void acceptsWorkspaceAndExtraRootsThatAreSymlinks() throws Exception {
        Path root = workspace();
        Path extra = Files.createDirectory(tmp.resolve("extra")).toRealPath();
        Path rootLink = tmp.resolve("workspace-link");
        Path extraLink = tmp.resolve("extra-link");
        symlink(rootLink, root);
        symlink(extraLink, extra);
        ToolSandbox sandbox = new ToolSandbox(rootLink, List.of(extraLink.toString()));

        assertEquals(root.resolve("a/b/c.txt"), sandbox.resolve("a/b/c.txt"));
        assertEquals(extra.resolve("a/b/c.txt"), sandbox.resolve(extraLink.resolve("a/b/c.txt").toString()));
    }

    @Test
    void rejectsBrokenSymlinkAsTargetOrAncestor() throws Exception {
        Path root = workspace();
        symlink(root.resolve("broken"), tmp.resolve("missing"));
        ToolSandbox sandbox = new ToolSandbox(root, List.of());
        assertThrows(ToolException.class, () -> sandbox.resolve("broken"));
        assertThrows(ToolException.class, () -> sandbox.resolve("broken/a/b/c.txt"));
    }
}
