package llm;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import core.Message;
import test.Assert;

/**
 * llm.AnthropicChatClient —— Anthropic Messages API 客户端。
 * RED:类不存在,编译失败即特性缺失。
 *
 * 用 JDK 内置 com.sun.net.httpserver 起本地假网关(不联网),验证:
 * 1. 请求体映射:system 抽到顶层、messages 只含 user/assistant、含 max_tokens、带 Bearer 头;
 * 2. 响应解析:从 content[0].text 读模型回答;
 * 3. 错误处理:非 200 / 非 JSON 抛含原始响应片段的异常。
 */
public class AnthropicChatClientTest {

    public static void main(String[] args) throws Exception {
        testRequestBodyMapping();
        testResponseParsing();
        testErrorSurfacesResponseSnippet();
        testTrailingSpaceInBaseUrlTolerated();
        System.out.println("PASS " + AnthropicChatClientTest.class.getName());
    }

    /** 起本地假网关:捕获请求体与认证头,回 canned 响应。 */
    private static HttpServer startServer(AtomicReference<String> body, AtomicReference<String> auth,
                                          String cannedBody, int status) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] resp = cannedBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        return server;
    }

    private static AnthropicChatClient clientFor(HttpServer server, String apiKey) {
        HttpClient http = HttpClient.newBuilder().build();
        return new AnthropicChatClient(http, apiKey,
                "http://localhost:" + server.getAddress().getPort() + "/v1", "test-model", 1024);
    }

    private static void testRequestBodyMapping() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        HttpServer server = startServer(body, auth, "{\"content\":[{\"type\":\"text\",\"text\":\"去故宫\"}]}", 200);
        try {
            AnthropicChatClient client = clientFor(server, "sk-test-key");
            client.chat(List.of(
                    Message.system("你是旅行助手"),
                    Message.user("推荐北京"),
                    Message.assistant("我查下天气"),
                    Message.user("工具返回: 北京晴")
            ));

            String req = body.get();
            Assert.true_(req.contains("\"system\":\"你是旅行助手\""), "system 应抽到顶层,实际=" + req);
            Assert.true_(!req.contains("\"role\":\"system\""), "messages 不应含 system 角色,实际=" + req);
            Assert.true_(req.contains("\"max_tokens\":1024"), "应含 max_tokens,实际=" + req);
            Assert.true_(req.contains("\"role\":\"user\""), "应含 user 角色,实际=" + req);
            Assert.true_(req.contains("\"role\":\"assistant\""), "应含 assistant 角色,实际=" + req);
            Assert.equal("Bearer sk-test-key", auth.get());
        } finally {
            server.stop(0);
        }
    }

    private static void testResponseParsing() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        HttpServer server = startServer(body, auth, "{\"content\":[{\"type\":\"text\",\"text\":\"推荐去故宫\"}]}", 200);
        try {
            AnthropicChatClient client = clientFor(server, "sk-test-key");
            String answer = client.chat(List.of(Message.user("推荐北京")));
            Assert.equal("推荐去故宫", answer);
        } finally {
            server.stop(0);
        }
    }

    private static void testErrorSurfacesResponseSnippet() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        HttpServer server = startServer(body, auth, "<html>error page</html>", 200);
        try {
            AnthropicChatClient client = clientFor(server, "sk-test-key");
            boolean threw = false;
            try {
                client.chat(List.of(Message.user("推荐北京")));
            } catch (IllegalStateException e) {
                threw = true;
                Assert.true_(e.getMessage().contains("<html>"), "异常应含原始响应片段,实际=" + e.getMessage());
            }
            Assert.true_(threw, "非 JSON 响应应抛异常");
        } finally {
            server.stop(0);
        }
    }

    /** 防御:环境变量的 base_url 误带尾随空格也不该崩(应被 trim)。 */
    private static void testTrailingSpaceInBaseUrlTolerated() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        HttpServer server = startServer(body, auth, "{\"content\":[{\"type\":\"text\",\"text\":\"推荐去故宫\"}]}", 200);
        try {
            AnthropicChatClient client = new AnthropicChatClient(HttpClient.newBuilder().build(), "sk-test-key",
                    "http://localhost:" + server.getAddress().getPort() + "/v1 ", "test-model", 1024);
            String answer = client.chat(List.of(Message.user("推荐北京")));
            Assert.equal("推荐去故宫", answer);
        } finally {
            server.stop(0);
        }
    }
}