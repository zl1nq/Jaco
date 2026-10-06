package com.jaco.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 加载 ~/.jaco/config.yaml。字符串值中的 ${ENV_VAR} 会被环境变量替换，
 * 因此 api_key 既可以走环境变量，也可以直接写进文件。
 */
public final class ConfigLoader {

    private static final Pattern ENV_PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_]+)}");

    private ConfigLoader() {
    }

    /** 配置文件不存在时返回 null，由调用方负责给出引导提示。 */
    public static JacoConfig load(Path home) throws IOException {
        Path file = home.resolve("config.yaml");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        JsonNode tree = mapper.readTree(file.toFile());
        substituteEnv(tree);
        return mapper.treeToValue(tree, JacoConfig.class);
    }

    private static void substituteEnv(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
            while (it.hasNext()) {
                var field = it.next();
                JsonNode replaced = substituteText(field.getValue());
                if (replaced != null) {
                    obj.set(field.getKey(), replaced);
                } else {
                    substituteEnv(field.getValue());
                }
            }
        } else if (node instanceof ArrayNode arr) {
            for (int i = 0; i < arr.size(); i++) {
                JsonNode replaced = substituteText(arr.get(i));
                if (replaced != null) {
                    arr.set(i, replaced);
                } else {
                    substituteEnv(arr.get(i));
                }
            }
        }
    }

    /** 叶子文本节点做占位符替换；发生了替换则返回新节点，否则返回 null。 */
    private static JsonNode substituteText(JsonNode node) {
        if (!(node instanceof TextNode text)) {
            return null;
        }
        String value = text.textValue();
        Matcher m = ENV_PLACEHOLDER.matcher(value);
        StringBuilder sb = new StringBuilder();
        boolean changed = false;
        while (m.find()) {
            changed = true;
            String env = System.getenv(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(env == null ? "" : env));
        }
        m.appendTail(sb);
        return changed ? TextNode.valueOf(sb.toString()) : null;
    }
}
