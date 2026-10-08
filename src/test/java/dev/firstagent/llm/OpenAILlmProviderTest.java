package dev.firstagent.llm;

import dev.firstagent.AssistantReply;
import dev.firstagent.Message;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

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

    // 离线：本地 HttpServer 捕获请求体，断言带 tools 声明（真实闭环硬前提）
    @Test void requestBodyContainsToolsDeclarations() throws Exception {
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> body = new AtomicReference<>();
        server.createContext("/", ex -> {
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String resp = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}";
            byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        try {
            String addr = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            OpenAILlmProvider p = new OpenAILlmProvider("test-key", addr, "test-model",
                    List.of(new ToolSpec("calculate", "do math")));
            p.chat(List.of(Message.user("1+1")));

            String b = body.get();
            org.junit.jupiter.api.Assertions.assertTrue(b.contains("\"tools\""), "body 应含 tools: " + b);
            org.junit.jupiter.api.Assertions.assertTrue(b.contains("\"calculate\""), "body 应含工具名: " + b);
        } finally {
            server.stop(0);
        }
    }
}