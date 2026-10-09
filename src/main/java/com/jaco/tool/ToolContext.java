package com.jaco.tool;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** 工具执行上下文：每个 turn 构建一次，所有工具共享。 */
public record ToolContext(
        Path workspaceRoot,
        ToolSandbox sandbox,
        String shellChoice,
        AtomicReference<Process> currentProcess,
        java.util.function.BooleanSupplier cancelled,
        Path archiveFile,
        int maxToolResultChars) {

    public ToolContext(Path workspaceRoot, ToolSandbox sandbox, String shellChoice,
                       AtomicReference<Process> currentProcess,
                       java.util.function.BooleanSupplier cancelled, Path archiveFile) {
        this(workspaceRoot, sandbox, shellChoice, currentProcess, cancelled, archiveFile, 8000);
    }
}
