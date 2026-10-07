package com.jaco.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 单行词法着色（不做语法树）：字符串/注释/数字/关键词/注解。
 * 多行结构（块注释、三引号）按行近似——单行内的规则足够覆盖终端阅读场景。
 * 输出 Span 序列，颜色为语义色。
 */
final class CodeHighlighter {

    private static final Map<String, Set<String>> KEYWORDS = Map.of(
            "java", Set.of("abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
                    "const", "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally",
                    "float", "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long",
                    "native", "new", "package", "private", "protected", "public", "record", "return", "sealed",
                    "short", "static", "super", "switch", "synchronized", "this", "throw", "throws", "transient",
                    "try", "var", "void", "volatile", "while", "yield", "true", "false", "null"),
            "python", Set.of("and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del",
                    "elif", "else", "except", "finally", "for", "from", "global", "if", "import", "in", "is",
                    "lambda", "None", "nonlocal", "not", "or", "pass", "raise", "return", "True", "False", "try",
                    "while", "with", "yield"),
            "javascript", Set.of("async", "await", "break", "case", "catch", "class", "const", "continue",
                    "default", "delete", "do", "else", "export", "extends", "finally", "for", "function", "if",
                    "import", "in", "instanceof", "let", "new", "null", "of", "return", "static", "super",
                    "switch", "this", "throw", "true", "false", "try", "typeof", "undefined", "var", "void",
                    "while", "yield"),
            "bash", Set.of("break", "case", "continue", "do", "done", "elif", "else", "esac", "exit", "export",
                    "fi", "for", "function", "if", "in", "local", "return", "then", "until", "while"));

    /** 围栏语言名 → 归一化的键 */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("py", "python"), Map.entry("python3", "python"),
            Map.entry("js", "javascript"), Map.entry("ts", "javascript"),
            Map.entry("typescript", "javascript"), Map.entry("jsx", "javascript"),
            Map.entry("tsx", "javascript"), Map.entry("node", "javascript"),
            Map.entry("sh", "bash"), Map.entry("shell", "bash"),
            Map.entry("zsh", "bash"), Map.entry("console", "bash"),
            Map.entry("json", "json"), Map.entry("jsonc", "json"));

    private final String langKey;
    private final Set<String> keywords;
    private final boolean hashComments; // python/bash 的 # 注释
    private final boolean isJson;

    CodeHighlighter(String fenceLang) {
        String key = fenceLang == null ? "" : fenceLang.strip().toLowerCase();
        key = ALIASES.getOrDefault(key, KEYWORDS.containsKey(key) ? key : "");
        this.langKey = key;
        this.keywords = KEYWORDS.getOrDefault(key, Set.of());
        this.hashComments = key.equals("python") || key.equals("bash");
        this.isJson = key.equals("json");
    }

    boolean supported() {
        return !langKey.isEmpty();
    }

    /** 渲染单行代码（不含换行）为带样式的 Span。不认识的语言返回单段原文。 */
    List<Span> highlight(String line) {
        if (!supported() || line.isEmpty()) {
            return List.of(Span.plain(line));
        }
        List<Span> out = new ArrayList<>();
        int i = 0;
        int len = line.length();
        while (i < len) {
            char c = line.charAt(i);

            // 注释起始（到行尾）
            if (c == '#' && hashComments && (i == 0 || line.charAt(i - 1) != '$')) {
                out.add(Span.of(line.substring(i), Color.COMMENT).withItalic());
                return out;
            }
            if (c == '/' && i + 1 < len && line.charAt(i + 1) == '/' && !isJson) {
                out.add(Span.of(line.substring(i), Color.COMMENT).withItalic());
                return out;
            }
            if (c == '/' && i + 1 < len && line.charAt(i + 1) == '*' && !isJson) {
                int close = line.indexOf("*/", i + 2);
                int end = close >= 0 ? close + 2 : len;
                out.add(Span.of(line.substring(i, end), Color.COMMENT).withItalic());
                i = end;
                continue;
            }

            // 字符串
            if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < len) {
                    if (line.charAt(j) == '\\' && j + 1 < len) {
                        j += 2;
                        continue;
                    }
                    if (line.charAt(j) == c) {
                        j++;
                        break;
                    }
                    j++;
                }
                String text = line.substring(i, Math.min(j, len));
                Color color = Color.STRING;
                if (isJson) {
                    // "key": 的键染天蓝，纯值染草绿
                    String rest = line.substring(Math.min(j, len)).stripLeading();
                    if (rest.startsWith(":")) {
                        color = Color.JSON_KEY;
                    }
                }
                out.add(Span.of(text, color));
                i = j;
                continue;
            }

            // 注解 @Word（java）
            if (c == '@' && i + 1 < len && Character.isJavaIdentifierStart(line.charAt(i + 1))) {
                int j = i + 1;
                while (j < len && Character.isJavaIdentifierPart(line.charAt(j))) {
                    j++;
                }
                out.add(Span.of(line.substring(i, j), Color.ANNOTATION));
                i = j;
                continue;
            }

            // 词法单元
            if (Character.isJavaIdentifierStart(c)) {
                int j = i;
                while (j < len && (Character.isJavaIdentifierPart(line.charAt(j)) || line.charAt(j) == '#')) {
                    j++;
                }
                String word = line.substring(i, j);
                if (keywords.contains(word)) {
                    out.add(Span.of(word, Color.KEYWORD));
                } else {
                    out.add(Span.plain(word));
                }
                i = j;
                continue;
            }

            // 数字
            if (Character.isDigit(c)) {
                int j = i;
                while (j < len && (Character.isDigit(line.charAt(j)) || line.charAt(j) == '.'
                        || line.charAt(j) == '_')) {
                    j++;
                }
                out.add(Span.of(line.substring(i, j), Color.NUMBER));
                i = j;
                continue;
            }

            out.add(Span.plain(String.valueOf(c)));
            i++;
        }
        return out;
    }
}
