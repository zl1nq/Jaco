package com.jaco.tool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** 取消时先捕获并终止子进程，再终止 shell；等待和清理结果由执行线程处理。 */
public final class ProcessTree {
    private ProcessTree() {
    }

    public static List<ProcessHandle> descendants(Process process) {
        try {
            return process.descendants().toList();
        } catch (SecurityException | UnsupportedOperationException e) {
            return List.of();
        }
    }

    public static void terminate(Process process) {
        terminate(descendants(process));
        // Process.destroyForcibly 也适用于不支持 ProcessHandle 的平台。
        if (process.isAlive()) {
            try {
                process.destroyForcibly();
            } catch (SecurityException | UnsupportedOperationException ignored) {
                // 执行线程会报告仍存活的进程。
            }
        }
    }

    public static void terminate(Collection<ProcessHandle> processes) {
        List<ProcessHandle> snapshot = new ArrayList<>(processes);
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            try {
                snapshot.get(i).destroyForcibly();
            } catch (SecurityException | UnsupportedOperationException ignored) {
                // 尽力清理其余进程，执行线程随后检查存活状态并返回警告。
            }
        }
    }
}
