package com.jaco.llm;

/** 上游返回 4xx 等不可重试错误时抛出，message 已提取为可读文本。 */
public class JacoApiException extends RuntimeException {

    private final int statusCode;

    public JacoApiException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
