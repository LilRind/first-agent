package tools;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import util.Json;

/**
 * 工具 1：查询真实天气 —— 对应书本的 get_weather(city)。
 *
 * 书本 Python 用 requests.get("https://wttr.in/{city}?format=j1")，
 * Java 里我们用 JDK 内置的 HttpClient 等价实现，返回值同样是自然语言句子。
 */
public class WeatherTool implements Tool {

    private static final String WTTR_ENDPOINT = "https://wttr.in/%s?format=j1";

    private final HttpClient http;

    public WeatherTool() {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public String name() {
        return "get_weather";
    }

    @Override
    public String description() {
        return "get_weather(city: str): 查询指定城市的实时天气。";
    }

    @Override
    public String execute(Map<String, String> arguments) {
        String city = arguments.get("city");
        if (city == null || city.isBlank()) {
            return "错误:缺少参数 city。";
        }
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(WTTR_ENDPOINT.formatted(city)))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return "错误:wttr.in 返回状态码 " + resp.statusCode();
            }
            var root = Json.parseObject(resp.body());
            String desc = Json.getString(root, "current_condition[0].weatherDesc[0].value");
            String temp = Json.getString(root, "current_condition[0].temp_C");
            if (desc == null) {
                return "错误:解析天气数据失败，可能是城市名称无效 - " + city;
            }
            return city + "当前天气:" + desc + "，气温" + temp + "摄氏度";
        } catch (Exception e) {
            return "错误:查询天气时遇到网络问题 - " + e.getMessage();
        }
    }
}
