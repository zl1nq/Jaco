package com.jaco.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件工具的路径沙箱：resolve 后必须落在允许的根目录之内（默认工作目录 + 配置的额外根）。
 * 存在的路径会做 toRealPath 解析，防符号链接逃逸。
 */
public final class ToolSandbox {

    private final List<Path> roots;

    public ToolSandbox(Path workspaceRoot, List<String> extraRoots) {
        List<Path> all = new ArrayList<>();
        all.add(workspaceRoot.toAbsolutePath().normalize());
        if (extraRoots != null) {
            extraRoots.stream()
                    .map(r -> Path.of(r).toAbsolutePath().normalize())
                    .forEach(all::add);
        }
        this.roots = List.copyOf(all);
    }

    /** 解析并校验路径；越界抛 ToolException。 */
    public Path resolve(String path) {
        Path p = Path.of(path);
        Path absolute = p.isAbsolute() ? p : roots.get(0).resolve(p);
        absolute = normalize(absolute);
        for (Path root : roots) {
            if (absolute.startsWith(root)) {
                return absolute;
            }
        }
        throw new ToolException("路径越界: " + path + " 只允许访问 " + roots + " 内的文件");
    }

    private Path normalize(Path p) {
        try {
            if (Files.exists(p)) {
                return p.toRealPath();
            }
            // 不存在的路径（写场景）：用已存在的最近祖先做真实路径校验，防符号链接逃逸
            Path parent = p.getParent();
            while (parent != null) {
                if (Files.exists(parent)) {
                    return parent.toRealPath().resolve(p.getFileName());
                }
                parent = parent.getParent();
            }
        } catch (IOException e) {
            throw new ToolException("路径解析失败: " + p + " (" + e.getMessage() + ")");
        }
        return p.normalize();
    }
}
