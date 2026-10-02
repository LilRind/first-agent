package llm;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;

import core.Message;
import test.Assert;

/**
 * llm.OpenAiCompatibleChatClient —— OpenAI 协议客户端。
 * RED:尚无可注入构造(can inject HttpClient + baseUrl),编译失败即特性缺失。
 *
 * 用本地假网关(纯 JDK HttpServer,不联网)验证:解析失败时抛的异常含原始响应片段,
 * 便于定位网关返回非 JSON 的情况(而非裸 NumberFormatException)。
 */
public class OpenAiCompatibleChatClientTest {

    public static void main(String[] args) throws Exception {
        testNonJsonResponseSurfacesSnippet();
        testTrailingSpaceInBaseUrlTolerated();
        System.out.println("PASS " + OpenAiCompatibleChatClientTest.class.getName());
    }

    /** 防御:base_url 误带尾随空格也不该崩(应被 trim)。 */
    private static void testTrailingSpaceInBaseUrlTolerated() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] resp = "{\"choices\":[{\"message\":{\"content\":\"推荐去故宫\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        try {
            OpenAiCompatibleChatClient client = new OpenAiCompatibleChatClient(HttpClient.newBuilder().build(),
                    "sk-key", "http://localhost:" + server.getAddress().getPort() + "/v1 ", "test-model");
            String answer = client.chat(List.of(Message.user("推荐北京")));
            Assert.equal("推荐去故宫", answer);
        } finally {
            server.stop(0);
        }
    }

    private static void testNonJsonResponseSurfacesSnippet() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] resp = "<html>bad gateway</html>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        try {
            HttpClient http = HttpClient.newBuilder().build();
            OpenAiCompatibleChatClient client = new OpenAiCompatibleChatClient(http, "sk-key",
                    "http://localhost:" + server.getAddress().getPort() + "/v1", "test-model");
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
}