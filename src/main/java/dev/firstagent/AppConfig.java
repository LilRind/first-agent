package dev.firstagent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 本地配置 —— 薄 file→map 读取器，专为「不想把 key 写死在代码里」。
 *
 * 三个来源，优先级从高到低：
 *   1. 工作目录下的 .env 文件（每行 KEY=VALUE，可空值表示"未配置"）
 *   2. 系统环境变量
 *   3. 代码默认值（由调用方在 get 时传入）
 *
 * OpenAILlmProvider 用它读 OPENAI_API_KEY / OPENAI_BASE_URL / OPENAI_MODEL。
 * .env 已在 .gitignore 里，绝不会被提交/推送。
 */
public class AppConfig {
    private final Map<String, String> fileVars = new HashMap<>();

    public AppConfig() {
        this(Path.of(".env"));
    }

    /** Explicit path constructor for isolated tests and alternate local config files. */
    public AppConfig(Path envPath) {
        loadDotEnv(envPath);
    }

    /** 找出第一个非空值：.env 文件 > 环境变量 > 传入的默认值。keepEmpty=false 表示忽略 .env 里的空值。 */
    public String get(String key, String defaultValue, boolean keepEmpty) {
        Optional<String> fromFile = Optional.ofNullable(fileVars.getOrDefault(key, null))
                .filter(v -> keepEmpty || !v.isBlank());
        Optional<String> fromEnv = Optional.ofNullable(System.getenv(key))
                .filter(v -> !v.isBlank());
        return fromFile.or(() -> fromEnv).orElse(defaultValue);
    }

    /** 便捷：忽略 .env 空值行为；默认走 keepEmpty=false。 */
    public String get(String key, String defaultValue) {
        return get(key, defaultValue, false);
    }

    /** 是否有非空配置（用于决定要不要走真实 provider，而不只是拿默认值）。 */
    public boolean has(String key) {
        String v = get(key, null);
        return v != null && !v.isBlank();
    }

    private void loadDotEnv(Path env) {
        if (!Files.exists(env)) return;
        try {
            for (String raw : Files.readAllLines(env)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;   // 跳过空行/注释
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                if (!key.isBlank()) fileVars.put(key, stripQuotes(value));
            }
        } catch (IOException e) {
            throw new IllegalStateException(".env 读取失败: " + env.toAbsolutePath(), e);
        }
    }

    private static String stripQuotes(String v) {
        if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }
}