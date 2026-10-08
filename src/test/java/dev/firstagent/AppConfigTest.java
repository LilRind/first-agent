package dev.firstagent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AppConfigTest {

    @TempDir Path tmp;

    /** .env 值优先于环境变量和默认值。 */
    @Test void dotEnvValueWins() throws IOException {
        Path env = tmp.resolve(".env");
        Files.writeString(env, "FOO=fromfile\n");
        AppConfig cfg = new AppConfig(env);
        assertEquals("fromfile", cfg.get("FOO", "default"));
    }

    /** .env 里留空的行 → 跳过，回退环境变量/默认值。 */
    @Test void emptyDotEnvValueFallsThrough() throws IOException {
        Path env = tmp.resolve(".env");
        Files.writeString(env, "BAR=\n");
        AppConfig cfg = new AppConfig(env);
        assertEquals("default", cfg.get("BAR", "default"));
    }

    /** 有注释和空行时解析安全。 */
    @Test void skipsCommentsAndBlankLines() throws IOException {
        Path env = tmp.resolve(".env");
        Files.writeString(env, "# comment\n\nKEY=val\n");
        AppConfig cfg = new AppConfig(env);
        assertEquals("val", cfg.get("KEY", "default"));
        assertNull(cfg.get("comment", null));
    }

    /** 没 .env 文件时用默认值。 */
    @Test void noDotEnvFallsBackToDefault() {
        AppConfig cfg = new AppConfig(tmp.resolve(".env"));   // 不存在
        assertEquals("default", cfg.get("MISSING", "default"));
    }

    /** has() 在 .env 空值时返回 false（不误判为已配置）。 */
    @Test void hasReturnsFalseOnEmptyDotEnv() throws IOException {
        Path env = tmp.resolve(".env");
        Files.writeString(env, "KEY=\n");
        AppConfig cfg = new AppConfig(env);
        assertFalse(cfg.has("KEY"));
    }

    /** 带引号的值会剥掉引号。 */
    @Test void stripsQuotes() throws IOException {
        Path env = tmp.resolve(".env");
        Files.writeString(env, "KEY=\"quoted\"\n");
        AppConfig cfg = new AppConfig(env);
        assertEquals("quoted", cfg.get("KEY", null));
    }
}