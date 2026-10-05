package dev.firstagent.telemetry;

import java.util.Map;

/** 一条 span —— name + 属性 + 起止纳秒 + 可选 parentId。对齐 pi 的 TelemetrySpan（简化）。 */
public record TelemetrySpan(String name, Map<String, String> attributes,
                            long startNanos, long endNanos, String parentId) {
    public long durationNanos() { return endNanos - startNanos; }
}
