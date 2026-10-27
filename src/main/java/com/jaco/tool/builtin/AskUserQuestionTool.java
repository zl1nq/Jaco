package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jaco.agent.TurnEvent;
import com.jaco.agent.TurnEventSink;
import com.jaco.hook.AgentHook;
import com.jaco.tool.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** 通过宿主事件提问；不读取 stdin、不把默认偏好当成权限授权。 */
public final class AskUserQuestionTool implements Tool, AgentHook, AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private final AtomicLong generation = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();

    @Override public String name() { return "ask_user_question"; }
    @Override public String description() {
        return "仅主任务使用：向用户逐题提问，一次 1～3 题。选项题提供 2～5 项且恰好一个 recommended=true，"
                + "必须说明 recommendation_reason，同时始终支持自由输入。空提交或主动跳过采用推荐项；静默等待不自动选择。"
                + "省略 options 为纯文本题，空提交需填写，跳过为未回答。只在缺失信息会影响工作且无法合理判断时提问。"
                + "答案 source 区分用户选择、自定义和默认采用；推荐默认不是用户明确选择，也不授予写入或命令权限。";
    }
    @Override public JsonNode schema() {
        ObjectNode root = (ObjectNode) JsonSchema.object().required("questions").build();
        root.put("additionalProperties", false);
        ObjectNode questions = root.withObject("/properties").putObject("questions");
        questions.put("type", "array").put("minItems", 1).put("maxItems", 3);
        ObjectNode question = (ObjectNode) JsonSchema.object()
                .string("id", "唯一问题 ID，1～64 位字母、数字、下划线或连字符")
                .string("header", "可选短标题，最多 40 字符")
                .string("question", "完整问题，最多 1000 字符")
                .string("recommendation_reason", "有选项时必填，解释推荐原因，最多 300 字符")
                .bool("multi_select", "第一版仅支持 false，默认 false")
                .required("id", "question").build();
        question.put("additionalProperties", false);
        question.withObject("/properties/multi_select").putArray("enum").add(false);
        ObjectNode options = question.withObject("/properties").putObject("options");
        options.put("type", "array").put("minItems", 2).put("maxItems", 5);
        ObjectNode option = (ObjectNode) JsonSchema.object().string("id", "本题唯一选项 ID")
                .string("label", "选项名称，最多 100 字符").string("description", "可选说明，最多 300 字符")
                .bool("recommended", "恰好一个选项为 true；省略为 false").required("id", "label").build();
        option.put("additionalProperties", false);
        options.set("items", option);
        questions.set("items", question);
        return root;
    }
    @Override public String summary(JsonNode args) { return "向用户提问（" + args.path("questions").size() + " 题）"; }

    @Override public PreparedToolCall prepare(JsonNode args, ToolContext ctx) {
        List<UserQuestion> questions = parse(args);
        long epoch = generation.get();
        return new PreparedToolCall(null, () -> ask(questions, ctx, epoch));
    }
    @Override public String execute(JsonNode args, ToolContext ctx) throws Exception { return prepare(args, ctx).execute(); }

    private String ask(List<UserQuestion> questions, ToolContext ctx, long epoch) throws Exception {
        TurnEventSink sink = TurnEventSink.current();
        if (sink != null && sink.taskId() != null) throw new ToolException("子任务不能直接向用户提问，请交由主任务处理");
        if (sink == null) return MAPPER.createObjectNode().put("status", "unavailable")
                .put("reason", "没有可用的交互通道，未采用任何默认项").set("answers", MAPPER.createArrayNode()).toString();
        BooleanSupplier current = () -> !closed.get() && generation.get() == epoch && !ctx.cancelled().getAsBoolean()
                && !sink.cancelled();
        List<UserQuestion.Answer> answers = new ArrayList<>();
        String requestId = UUID.randomUUID().toString();
        for (UserQuestion question : questions) {
            check(current, ctx, sink);
            CompletableFuture<UserQuestion.Answer> response = new CompletableFuture<>();
            try {
                sink.emit(new TurnEvent.QuestionRequest(requestId, question, submission -> {
                    if (response.isDone() || !current.getAsBoolean()) return;
                    if (submission == null || !requestId.equals(submission.requestId())) throw new ToolException("回答的请求 ID 不匹配");
                    UserQuestion.Answer answer = question.answer(submission);
                    if (current.getAsBoolean()) response.complete(answer);
                }, () -> current.getAsBoolean() && !response.isDone()));
                while (true) {
                    check(current, ctx, sink);
                    try {
                        var answer = response.get(100, TimeUnit.MILLISECONDS);
                        check(current, ctx, sink);
                        answers.add(answer);
                        break;
                    } catch (TimeoutException ignored) { /* 用户未操作时继续等待，不采用默认项。 */ }
                }
            } finally { response.cancel(false); }
        }
        check(current, ctx, sink);
        long count = answers.stream().filter(a -> a.status().equals("answered")).count();
        ObjectNode result = MAPPER.createObjectNode().put("request_id", requestId)
                .put("status", count == answers.size() ? "answered" : count == 0 ? "skipped" : "partial");
        result.set("answers", MAPPER.valueToTree(answers));
        return result.toString();
    }
    private static void check(BooleanSupplier current, ToolContext ctx, TurnEventSink sink) throws InterruptedException {
        if (Thread.currentThread().isInterrupted() || ctx.cancelled().getAsBoolean() || sink.cancelled())
            throw new InterruptedException("提问已取消");
        if (!current.getAsBoolean()) throw new ToolException("提问已关闭或会话已切换，未采用默认项");
    }

    private static List<UserQuestion> parse(JsonNode args) {
        fields(args, Set.of("questions"));
        JsonNode array = args.get("questions");
        if (array == null || !array.isArray() || array.isEmpty() || array.size() > 3) throw new ToolException("questions 必须包含 1～3 个问题");
        List<UserQuestion> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode node : array) {
            fields(node, Set.of("id", "header", "question", "options", "recommendation_reason", "multi_select"));
            String id = id(node);
            if (!ids.add(id)) throw new ToolException("问题 ID 不能重复: " + id);
            String header = text(node, "header", 40, false);
            String question = text(node, "question", 1000, true);
            if (node.has("multi_select") && (!node.get("multi_select").isBoolean() || node.get("multi_select").booleanValue()))
                throw new ToolException("第一版 multi_select 只支持 false");
            List<UserQuestion.Option> options = new ArrayList<>();
            String reason = text(node, "recommendation_reason", 300, node.has("options"));
            if (node.has("options")) {
                JsonNode list = node.get("options");
                if (!list.isArray() || list.size() < 2 || list.size() > 5) throw new ToolException("options 必须包含 2～5 个选项");
                Set<String> optionIds = new HashSet<>();
                for (JsonNode option : list) {
                    fields(option, Set.of("id", "label", "description", "recommended"));
                    String optionId = id(option);
                    if (!optionIds.add(optionId)) throw new ToolException("选项 ID 不能重复: " + optionId);
                    if (option.has("recommended") && !option.get("recommended").isBoolean()) throw new ToolException("recommended 必须是布尔值");
                    options.add(new UserQuestion.Option(optionId, text(option, "label", 100, true),
                            text(option, "description", 300, false), option.path("recommended").asBoolean(false)));
                }
                if (options.stream().filter(UserQuestion.Option::recommended).count() != 1) throw new ToolException("每题必须恰好有一个推荐项");
            } else if (node.has("recommendation_reason")) throw new ToolException("纯文本题不能提供 recommendation_reason");
            result.add(new UserQuestion(id, header, question, options, reason));
        }
        return List.copyOf(result);
    }
    private static void fields(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject()) throw new ToolException("提问参数必须是 JSON 对象");
        node.fieldNames().forEachRemaining(name -> { if (!allowed.contains(name)) throw new ToolException("未知参数: " + name); });
    }
    private static String id(JsonNode node) {
        String value = text(node, "id", 64, true);
        if (!value.matches("[A-Za-z0-9_-]+")) throw new ToolException("id 只允许字母、数字、下划线或连字符");
        return value;
    }
    private static String text(JsonNode node, String name, int max, boolean required) {
        JsonNode value = node.get(name);
        if (value == null && !required) return "";
        if (value == null || !value.isTextual() || required && value.asText().isBlank()) throw new ToolException(name + " 必须是非空字符串");
        UserQuestion.validateText(value.asText(), max, name);
        return value.asText();
    }
    @Override public void onSessionChanged() { generation.incrementAndGet(); }
    @Override public void close() { closed.set(true); generation.incrementAndGet(); }
}
