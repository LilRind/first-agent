package llm;

import java.util.List;

/**
 * 通向 LLM 的抽象 —— 主循环只关心"给它一段对话历史，它回一句文本"。
 *
 * 为什么参数是 List<Message> 而不是单个字符串？因为 ReAct 循环每轮必须把
 * 完整历史（system 说明书 + 用户问题 + 之前的 Thought/Action/观测结果）一起
 * 发给 LLM，LLM 才能接着"思考下一步"。只发最后一句话等于让 LLM 失忆。
 * 所以抽象成一个 Message 列表，OpenAI 兼容接口、Mock 都能喂同一种东西。
 */
public interface ChatClient {

    /** 把一段对话历史发给 LLM，返回模型生成的纯文本回复。 */
    String chat(List<Message> messages);

    /** 一条消息：谁说的、说了什么。role 复用 OpenAI 的 system/user/assistant。 */
    record Message(String role, String content) {
        public static Message system(String content) {
            return new Message("system", content);
        }

        public static Message user(String content) {
            return new Message("user", content);
        }

        public static Message assistant(String content) {
            return new Message("assistant", content);
        }
    }
}
