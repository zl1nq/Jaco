package com.jaco.tool;

/** 工具执行失败（路径越界、文件不存在等），message 会原样回喂模型。 */
public class ToolException extends RuntimeException {

    public ToolException(String message) {
        super(message);
    }
}
