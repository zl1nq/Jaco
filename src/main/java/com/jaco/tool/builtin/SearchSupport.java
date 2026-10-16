package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaco.tool.ToolContext;
import com.jaco.tool.ToolException;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 两个只读搜索工具共享参数校验、目录剪枝和不跟随符号链接的遍历。 */
final class SearchSupport {
    private static final Set<String> IGNORED_DIRS = Set.of(
            ".git", ".svn", ".hg", "node_modules", "target", "build", "dist", ".gradle", ".idea", ".venv", "__pycache__");

    private SearchSupport() {
    }

    static void validate(JsonNode args, String... fields) {
        if (args == null || !args.isObject()) {
            throw new ToolException("搜索参数必须是 JSON 对象");
        }
        Set<String> allowed = Set.of(fields);
        args.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) {
                throw new ToolException("未知的搜索参数: " + name);
            }
        });
    }

    static String string(JsonNode args, String key, String fallback) {
        JsonNode value = args.get(key);
        if (value == null) {
            return fallback;
        }
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new ToolException(key + " 必须是非空白字符串");
        }
        return value.textValue();
    }

    static boolean bool(JsonNode args, String key) {
        JsonNode value = args.get(key);
        if (value != null && !value.isBoolean()) {
            throw new ToolException(key + " 必须是布尔值");
        }
        return value != null && value.booleanValue();
    }

    static int integer(JsonNode args, String key, int fallback, int min, int max) {
        JsonNode value = args.get(key);
        if (value == null) {
            return fallback;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < min || value.intValue() > max) {
            throw new ToolException(key + " 必须是 " + min + " 到 " + max + " 的整数");
        }
        return value.intValue();
    }

    /** 无斜杠的 glob 匹配文件名；有斜杠则匹配相对路径；开头的双星目录前缀也匹配零层。 */
    static final class Glob {
        private final boolean filenameOnly;
        private final List<PathMatcher> matchers = new ArrayList<>();

        Glob(String glob) {
            String normalized = glob.replace('\\', '/');
            filenameOnly = !normalized.contains("/");
            try {
                matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + normalized));
                while (normalized.startsWith("**/")) {
                    normalized = normalized.substring(3);
                    matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + normalized));
                }
            } catch (IllegalArgumentException e) {
                throw new ToolException("glob 无效: " + glob);
            }
        }

        boolean matches(Path relative) {
            Path candidate = filenameOnly ? relative.getFileName() : relative;
            return matchers.stream().anyMatch(m -> m.matches(candidate));
        }
    }

    static List<Glob> exclusions(JsonNode args) {
        List<Glob> globs = new ArrayList<>();
        JsonNode values = args.get("exclude");
        if (values != null) {
            if (!values.isArray()) {
                throw new ToolException("exclude 必须是 glob 字符串数组");
            }
            for (JsonNode value : values) {
                if (!value.isTextual() || value.textValue().isBlank()) {
                    throw new ToolException("exclude 中的 glob 必须是非空白字符串");
                }
                globs.add(new Glob(value.textValue()));
            }
        }
        return globs;
    }

    @FunctionalInterface
    interface FileSearch {
        boolean search(Path file, String relative) throws IOException;
    }

    static final class Scan {
        private int skipped;
        private final List<String> examples = new ArrayList<>();
        boolean limited;

        void skipped(String path, String reason) {
            skipped++;
            if (examples.size() < 3) {
                examples.add(path + " (" + reason + ")");
            }
        }

        String finish(List<String> lines) {
            String result = lines.isEmpty() ? "(no matches)" : String.join("\n", lines);
            if (limited) {
                result += "\n(已达到 max_results，搜索提前停止，可能还有结果；请收窄搜索范围)";
            }
            if (skipped > 0) {
                result += "\n(搜索不完整：跳过 " + skipped + " 项：" + String.join("; ", examples) + ")";
            }
            return result;
        }
    }

    static void checkCancelled(ToolContext ctx) {
        if (Thread.currentThread().isInterrupted() || ctx.cancelled().getAsBoolean()) {
            throw new ToolException("搜索已取消");
        }
    }

    static void walk(Path root, ToolContext ctx, List<Glob> exclude, boolean includeIgnored,
                     Scan scan, FileSearch search) throws IOException {
        checkCancelled(ctx);
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new ToolException("搜索路径不存在: " + root);
        }
        boolean singleFile = Files.isRegularFile(root, LinkOption.NOFOLLOW_LINKS);
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            private Path relative(Path path) {
                return singleFile ? path.getFileName() : root.relativize(path);
            }

            private boolean excluded(Path path) {
                return exclude.stream().anyMatch(g -> g.matches(relative(path)));
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                checkCancelled(ctx);
                if (!dir.equals(root) && ((!includeIgnored && IGNORED_DIRS.contains(dir.getFileName().toString()))
                        || excluded(dir))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                checkCancelled(ctx);
                if (attrs.isSymbolicLink() || Files.isSymbolicLink(file)) {
                    scan.skipped(relative(file).toString(), "符号链接");
                } else if (attrs.isRegularFile() && !excluded(file)) {
                    try {
                        // 每个文件再次校验真实路径，防止目录项指向允许范围之外。
                        Path safe = ctx.sandbox().resolve(file.toString());
                        if (!search.search(safe, relative(file).toString().replace('\\', '/'))) {
                            scan.limited = true;
                            return FileVisitResult.TERMINATE;
                        }
                    } catch (IOException e) {
                        scan.skipped(relative(file).toString(), e.getClass().getSimpleName());
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                checkCancelled(ctx);
                scan.skipped(relative(file).toString(), exc.getClass().getSimpleName());
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                checkCancelled(ctx);
                if (exc != null) {
                    scan.skipped(relative(dir).toString(), exc.getClass().getSimpleName());
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
