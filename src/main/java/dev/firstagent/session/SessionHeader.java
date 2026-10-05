package dev.firstagent.session;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/** JSONL 首行元数据,非树节点。name 可选(未命名时为 null,序列化时省略)。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SessionHeader(String type, int version, String id, String cwd,
                            String timestamp, String name) {
    static final int CURRENT_VERSION = 3;

    static SessionHeader of(String id, String cwd, Instant createdAt, String name) {
        return new SessionHeader("session", CURRENT_VERSION, id, cwd, createdAt.toString(), name);
    }

    Instant createdAt() { return Instant.parse(timestamp); }
}