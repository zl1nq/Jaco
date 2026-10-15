package com.jaco.memory;

import java.util.regex.Pattern;

/** 本地筛选只决定提取时机，长期信息的语义判断仍交给模型。 */
public final class ProfileExtractionPolicy {
    public enum Action { IMMEDIATE, BATCH, SKIP }
    public record Decision(Action action, String reason) { }

    private static final Pattern CONFIRMATION = Pattern.compile(
            "^(?:继续(?:吧|做|执行)?|好的?|嗯|行|收到|明白了?|谢谢|ok(?:ay)?|yes|thanks?|continue)[\\s。！!,.，]*$",
            Pattern.CASE_INSENSITIVE);
    private static final String POLITE = "(?:请(?:你|帮我)?|麻烦(?:你)?|帮我|能否|能不能|你能不能|可以(?:请你)?)?\\s*";
    private static final Pattern MEMORY_REQUEST = pattern("^" + POLITE
            + "(?:(?:记住|记一下|记下)(?!了?[吗么]|什么|怎么|如何)\\s*[:：]?\\s*\\S+|"
            + "(?:忘记|忘掉)(?:我(?:的|是|叫)|关于我的|之前(?:记录|记住|保存)的|这(?:个|条)(?:信息|偏好|记忆))\\S*|"
            + "(?:please\\s+)?(?:remember|forget)\\s+(?!(?:how|what|when|why)\\b).+)");
    private static final Pattern SELF_INTRODUCTION = pattern("^(?:"
            + "我的(?:职业|工作|名字|姓名|称呼)(?:是|叫)|我(?:目前|现在)?(?:从事|是一(?:名|位|个))|我叫(?![你您他她它])|"
            + "(?:以后)?" + POLITE + "(?:称呼我|叫我)(?!\\s*(?:去|来|帮|先|再|做|把|给))|my\\s+name\\s+is\\s+|(?:please\\s+)?call\\s+me\\s+|"
            + "i(?:\\s+am|'m)\\s+an?\\s+)\\s*\\S+");
    private static final Pattern BARE_IDENTITY = pattern(
            "^我(?:目前|现在)?是\\s*(?!来|说|想|要|指|在|因为|为了|这么|这样|怎么|如何|什么|谁|让|把|被|从|按|用|希望|问|对|就|已经|刚|好奇|觉得|考虑|通过|根据|打算|帮)"
                    + "[\\p{IsHan}a-z][\\p{IsHan}a-z0-9+.# /-]{0,29}$");
    private static final Pattern PERSONAL_PREFERENCE = pattern(
            "^(?:我(?:通常|平时|一向)?(?:更?喜欢|偏好|习惯|倾向于)\\s*\\S+|i\\s+(?:prefer|like)\\s+\\S+)");
    private static final Pattern CORRECTION = pattern("^(?:更正|纠正|更新)(?:一下)?\\s*[:：]?\\s*$");
    private static final Pattern PERSONAL_CHANGE = pattern(
            "^我(?:目前|现在|已经)?(?:不再是|不是(?!说|来|想|要)|改行|转行)\\S+");
    private static final Pattern LONG_TERM = pattern("^" + POLITE + "(?:以后|今后|往后|从现在起|默认|一直|总是)\\s*(.+)$");
    private static final Pattern RESPONSE_BEHAVIOR = pattern(
            "回答|回复|称呼|叫我|解释|沟通|交流|提交|代码风格|注释|命名|缩进|运行测试|单元测试|测试习惯|文档(?:格式|风格|写法)|打包|构建");
    private static final Pattern SHORT_RESPONSE = pattern("^(?:默认)?(?:用|使用)?(?:中文|英文|英语|简洁|简短|详细)[?？]?$");
    private static final Pattern TECHNICAL_SUBJECT = pattern(
            "^(?:这(?:个|段|些)|该|那个|上述)?(?:参数|配置|接口|函数|方法|字段|变量|选项|环境变量|超时|连接池|返回|输出|默认值|值|编码|属性|语言)");
    private static final Pattern PROJECT_CONVENTION = pattern(
            "^(?:这个|当前|本)(?:项目|仓库)(?:里|中)?(?:以后|今后|从现在起).{0,8}(?:统一|一律|都|固定)(?:使用|用|采用|遵循).+");
    private static final Pattern ENGLISH_RESPONSE = pattern(
            "^(?:please\\s+)?(?:from\\s+now\\s+on|always|by\\s+default)\\b[\\s,，]*(?:please\\s+)?"
                    + "(?:reply|respond|answer|address|explain|commit|write\\s+documentation)\\b");
    private static final Pattern QUESTION = pattern("[?？]$|[吗么呢]$|^(?:我的.{0,12}(?:什么|啥|哪个|哪种)|"
            + "我(?:是|叫)(?:什么|谁)|我(?:是不是|是否|喜欢什么|偏好什么|习惯什么|习惯怎么))");
    private static final Pattern FENCE = pattern("^ {0,3}(`{3,}|~{3,})(.*)$");
    private static final Pattern INLINE_CODE = Pattern.compile("(`+).*?\\1", Pattern.DOTALL);
    private static final Pattern CLAUSES = Pattern.compile("[^，,。.!！?？;；\\n]+[，,。.!！?？;；\\n]?");
    private static final Pattern ATTRIBUTED_QUOTE = Pattern.compile(
            "(?:[他她]说|(?:别人|客户|同事|用户)(?:说|写道)|(?:测试数据|文档|示例|例子|字符串)[^\\n。！？]{0,20}(?:有一句|写道|写着|包含|内容是|是))"
                    + "\\s*[:：]?\\s*(?:“[^”]*”|\"[^\"]*\"|「[^」]*」|『[^』]*』)");

    public static boolean shouldQueue(String prompt) {
        return classify(prompt).action() != Action.SKIP;
    }

    public static boolean immediate(String prompt) {
        return classify(prompt).action() == Action.IMMEDIATE;
    }

    public static Decision classify(String prompt) {
        if (prompt.isBlank() || CONFIRMATION.matcher(prompt.strip()).matches()) {
            return new Decision(Action.SKIP, "confirmation");
        }
        boolean correction = false;
        String prose = withoutQuotedCode(prompt);
        if (ENGLISH_RESPONSE.matcher(prose.strip()).find()) return new Decision(Action.IMMEDIATE, "response_preference");
        // 保留问号以便区分“我的职业是什么？”与自我介绍；偏好请求问句仍允许触发。
        var clauses = CLAUSES.matcher(prose);
        while (clauses.find()) {
            String clause = clauses.group().strip().replaceFirst("[，,。.!！;；]$", "").strip();
            if (MEMORY_REQUEST.matcher(clause).find()) return new Decision(Action.IMMEDIATE, "explicit_memory_request");
            if (!QUESTION.matcher(clause).find()) {
                if (SELF_INTRODUCTION.matcher(clause).find() || BARE_IDENTITY.matcher(clause).matches()) {
                    return new Decision(Action.IMMEDIATE, "self_introduction");
                }
                if (PERSONAL_CHANGE.matcher(clause).find()
                        || (correction && clause.matches("我(?:的|现在|目前|改|不再是).+"))) {
                    return new Decision(Action.IMMEDIATE, "personal_correction");
                }
                if (PERSONAL_PREFERENCE.matcher(clause).find()) return new Decision(Action.IMMEDIATE, "personal_preference");
            }
            var longTerm = LONG_TERM.matcher(clause);
            if (longTerm.matches()) {
                String behavior = longTerm.group(1);
                if (!TECHNICAL_SUBJECT.matcher(behavior).find() && !behavior.matches("^(?:再|等|有空).*")
                        && (RESPONSE_BEHAVIOR.matcher(behavior).find() || SHORT_RESPONSE.matcher(behavior).matches())) {
                    return new Decision(Action.IMMEDIATE, "response_preference");
                }
            }
            if (PROJECT_CONVENTION.matcher(clause).find()) return new Decision(Action.IMMEDIATE, "project_convention");
            if (ENGLISH_RESPONSE.matcher(clause).find()) return new Decision(Action.IMMEDIATE, "response_preference");
            correction = CORRECTION.matcher(clause).matches();
        }
        return new Decision(Action.BATCH, "no_explicit_intent");
    }

    /** 仅预处理触发判断；入队原文及最终证据校验仍使用原始消息。 */
    private static String withoutQuotedCode(String prompt) {
        StringBuilder prose = new StringBuilder();
        char fenceChar = 0;
        int fenceLength = 0;
        for (String line : prompt.split("\\R", -1)) {
            var fence = FENCE.matcher(line);
            if (fence.matches()) {
                String delimiter = fence.group(1);
                if (fenceChar == 0) {
                    fenceChar = delimiter.charAt(0);
                    fenceLength = delimiter.length();
                } else if (delimiter.charAt(0) == fenceChar && delimiter.length() >= fenceLength && fence.group(2).isBlank()) {
                    fenceChar = 0;
                }
                prose.append('\n');
                continue;
            }
            String text = line.strip();
            if (fenceChar != 0 || text.startsWith(">") || line.startsWith("    ") || line.startsWith("\t")
                    || text.matches("^[“\"「『].*[”\"」』]$")) {
                prose.append('\n');
                continue;
            }
            prose.append(line).append('\n');
        }
        return ATTRIBUTED_QUOTE.matcher(INLINE_CODE.matcher(prose).replaceAll(" ")).replaceAll(" ");
    }

    private static Pattern pattern(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private ProfileExtractionPolicy() {
    }
}
