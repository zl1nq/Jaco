package com.jaco.tool;

import java.util.List;

/** 提问及回答的不可变协议；答案来源由宿主推导，不接受模型或 UI 自报。 */
public record UserQuestion(String id, String header, String question, List<Option> options,
                           String recommendationReason) {
    public static final int MAX_ANSWER_LENGTH = 2000;
    public UserQuestion { options = List.copyOf(options); }
    public record Option(String id, String label, String description, boolean recommended) {}
    public record Submission(String requestId, String questionId, String selectedOptionId,
                             String customText, boolean skipped) {}
    public record Answer(String questionId, String status, List<String> selectedOptionIds,
                         String customText, String source, String defaultReason) {
        public Answer { selectedOptionIds = List.copyOf(selectedOptionIds); }
    }

    public Option recommended() { return options.stream().filter(Option::recommended).findFirst().orElse(null); }

    /** 终端编号只表示选项；/text 可显式输入数字或保留命令样式的自由文本。 */
    public Submission parseInput(String requestId, String input) {
        String text = input.strip();
        if (text.equals("/skip")) return new Submission(requestId, id, null, null, true);
        if (text.equals("/text") || text.startsWith("/text ")) {
            String custom = text.substring(5).strip();
            if (custom.isEmpty()) throw new ToolException("/text 后请填写答案");
            return new Submission(requestId, id, null, custom, false);
        }
        if (!options.isEmpty() && text.matches("[0-9]+")) {
            int index;
            try { index = Integer.parseInt(text); }
            catch (NumberFormatException e) { throw new ToolException("选项编号超出范围；输入数字文本请用 /text"); }
            if (index < 1 || index > options.size()) throw new ToolException("请选择 1～" + options.size() + "，输入数字文本请用 /text");
            return new Submission(requestId, id, options.get(index - 1).id(), null, false);
        }
        return new Submission(requestId, id, null, text, false);
    }

    public Answer answer(Submission submission) {
        if (!id.equals(submission.questionId())) throw new ToolException("回答的问题 ID 不匹配");
        String custom = submission.customText();
        if (custom != null) validateText(custom, MAX_ANSWER_LENGTH, "自定义答案");
        if (custom != null && !custom.isBlank()) {
            return new Answer(id, "answered", List.of(), custom.strip(), "custom_text", null);
        }
        if (submission.selectedOptionId() != null) {
            Option selected = options.stream().filter(o -> o.id().equals(submission.selectedOptionId())).findFirst()
                    .orElseThrow(() -> new ToolException("选择的选项 ID 不存在"));
            return new Answer(id, "answered", List.of(selected.id()), null, "user_selection", null);
        }
        Option recommended = recommended();
        if (recommended != null) return new Answer(id, "answered", List.of(recommended.id()), null,
                "recommended_default", submission.skipped() ? "skipped" : "empty_submit");
        if (submission.skipped()) return new Answer(id, "skipped", List.of(), null, "skipped", null);
        throw new ToolException("请填写答案，或输入 /skip 跳过");
    }

    public static void validateText(String text, int max, String label) {
        if (text.length() > max) throw new ToolException(label + " 最多 " + max + " 个字符");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) throw new ToolException(label + " 包含非法 Unicode");
            } else if (Character.isLowSurrogate(c)) throw new ToolException(label + " 包含非法 Unicode");
            else if (Character.isISOControl(c) && c != '\n' && c != '\t') throw new ToolException(label + " 包含不允许的控制字符");
        }
    }
}
