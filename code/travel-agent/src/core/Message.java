package core;

/**
 * 一条对话消息 —— 谁说的、说了什么。纯数据、不可变。
 *
 * role 对齐 OpenAI 的 4 种：system/user/assistant/tool。
 * 这是整个 agent loop 流转的最小载体：引擎回bit集装箱、工具观测结果都用它承载。
 * equals/hashCode 由 record 自动生成，保证同 role+content 即视为同一条消息。
 */
public record Message(String role, String content) {

    public static Message system(String content) {
        return new Message("system", content);
    }

    public static Message user(String content) {
        return new Message("user", content);
    }

    public static Message assistant(String content) {
        return new Message("assistant", content);
    }

    public static Message tool(String content) {
        return new Message("tool", content);
    }
}