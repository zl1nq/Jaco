package com.jaco.tui;

/** 斜杠命令。execute 返回 false 表示退出 REPL。 */
public interface Command {

    String name();

    String description();

    boolean execute(String args);

    /** 快速定义命令的适配器。 */
    record Simple(String name, String description, java.util.function.Function<String, Boolean> action)
            implements Command {

        @Override
        public boolean execute(String args) {
            return action.apply(args);
        }
    }
}
