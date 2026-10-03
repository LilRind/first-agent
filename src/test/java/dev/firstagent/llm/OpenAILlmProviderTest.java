package dev.firstagent.llm;

import dev.firstagent.AssistantReply;
import dev.firstagent.Message;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;

class OpenAILlmProviderTest {

    @Test void realCallRequiresKey() {
        Assumptions.assumeTrue(System.getenv("OPENAI_API_KEY") != null,
                "未设置 OPENAI_API_KEY，跳过集成测试（不影响 mvn test）");
        OpenAILlmProvider p = new OpenAILlmProvider();
        AssistantReply r = p.chat(List.of(
                Message.system("你是一个演示助手。"),
                Message.user("回复一个字：好")));
        System.out.println("OpenAI reply: " + r.text());  // 不崩、有内容即通过
    }
}