package tools;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import util.Json;

/**
 * 工具 2：搜索并推荐旅游景点 —— 对应书本的 get_attraction(city, weather)。
 *
 * 书本 Python 用 Tavily 官方 SDK（tavily-python）；Java 里没有这个 SDK，所以我们
 * 直接用 HttpClient 调 Tavily 的 HTTP API，效果等价。API key 从环境变量
 * TAVILY_API_KEY 读取，没配置或调用失败都优雅降级，不让整个 agent 崩掉。
 */
public class AttractionTool implements Tool {

    private static final String TAVILY_ENDPOINT = "https://api.tavily.com/search";

    private final HttpClient http;

    public AttractionTool() {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public String name() {
        return "get_attraction";
    }

    @Override
    public String description() {
        return "get_attraction(city: str, weather: str): 根据城市和天气搜索推荐的旅游景点。";
    }

    @Override
    public String execute(Map<String, String> arguments) {
        String apiKey = System.getenv("TAVILY_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            return "错误:未配置 TAVILY_API_KEY 环境变量。";
        }
        String city = arguments.get("city");
        String weather = arguments.get("weather");
        if (city == null || city.isBlank()) {
            return "错误:缺少参数 city。";
        }
        if (weather == null || weather.isBlank()) {
            weather = "晴天";
        }
        String query = "'" + city + "' 在'" + weather + "'天气下最值得去的旅游景点推荐及理由";
        try {
            // 手工拼一个 JSON 请求体，等价于 Tavily SDK 的 search()
            String body = """
                    {"api_key":"%s","query":"%s","search_depth":"basic","include_answer":true}
                    """.formatted(apiKey, query);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(TAVILY_ENDPOINT))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return "错误:Tavily 返回状态码 " + resp.statusCode();
            }
            var root = Json.parseObject(resp.body());

            // include_answer=true 时返回一个基于所有结果的总结性回答，优先用
            String answer = Json.getString(root, "answer");
            if (answer != null && !answer.isBlank()) {
                return answer;
            }
            // 否则格式化原始结果
            Object results = Json.get(root, "results");
            if (results instanceof java.util.List<?> list && !list.isEmpty()) {
                StringBuilder sb = new StringBuilder("根据搜索，为您找到以下信息:\n");
                for (Object r : list) {
                    if (r instanceof Map<?, ?> m) {
                        sb.append("- ").append(m.get("title")).append(": ").append(m.get("content")).append("\n");
                    }
                }
                return sb.toString().strip();
            }
            return "抱歉，没有找到相关的旅游景点推荐。";
        } catch (Exception e) {
            return "错误:执行 Tavily 搜索时出现问题 - " + e.getMessage();
        }
    }
}
