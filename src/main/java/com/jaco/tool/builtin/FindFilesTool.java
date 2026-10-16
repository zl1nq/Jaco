package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaco.tool.JsonSchema;
import com.jaco.tool.Tool;
import com.jaco.tool.ToolContext;
import com.jaco.tool.ToolException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 按名称或相对路径定位文件，无需执行 shell 命令。 */
public final class FindFilesTool implements Tool {
    @Override
    public String name() {
        return "find_files";
    }

    @Override
    public String description() {
        return "按 glob 定位文件，返回搜索根下的相对路径；无斜杠匹配文件名，有斜杠匹配相对路径。"
                + "例如 *.java、**/*Test.java；开头的 **/ 也匹配根目录文件。"
                + "默认跳过常见依赖/构建目录，不跟随子项符号链接；达到 max_results 提前停止，无分页。";
    }

    @Override
    public JsonNode schema() {
        return JsonSchema.object()
                .string("pattern", "文件名或相对路径 glob，如 **/*Tool.java")
                .string("path", "搜索目录或文件，默认工作目录")
                .integer("max_results", "结果上限，默认 100，范围 1-1000")
                .strings("exclude", "额外排除文件或目录的 glob 数组")
                .bool("include_ignored", "是否搜索默认排除目录，默认 false；不影响 exclude")
                .required("pattern").build();
    }

    @Override
    public String summary(JsonNode args) {
        return args.path("pattern").asText("?") + " in " + args.path("path").asText(".");
    }

    @Override
    public String execute(JsonNode args, ToolContext ctx) throws IOException {
        SearchSupport.validate(args, "pattern", "path", "max_results", "exclude", "include_ignored");
        String pattern = SearchSupport.string(args, "pattern", null);
        if (pattern == null) {
            throw new ToolException("pattern 必须提供非空白字符串");
        }
        SearchSupport.Glob glob = new SearchSupport.Glob(pattern);
        Path root = ctx.sandbox().resolve(SearchSupport.string(args, "path", "."));
        int max = SearchSupport.integer(args, "max_results", 100, 1, 1000);
        boolean includeIgnored = SearchSupport.bool(args, "include_ignored");
        List<String> results = new ArrayList<>();
        SearchSupport.Scan scan = new SearchSupport.Scan();
        SearchSupport.walk(root, ctx, SearchSupport.exclusions(args), includeIgnored, scan, (file, relative) -> {
            if (glob.matches(Path.of(relative))) {
                results.add(relative);
            }
            return results.size() < max;
        });
        return scan.finish(results);
    }
}
