package dev.firstagent.telemetry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 内存 span 录制器：记录已完成 span，供测试断言 / CLI 打印。线程安全(同步块)。 */
public final class TelemetryRecorder {
    private final List<TelemetrySpan> spans = new ArrayList<>();

    /** 记录一条起止瞬时(时长 0)的 span；attrs 为空则用空 map。 */
    public void record(String name, Map<String, String> attributes) {
        long now = System.nanoTime();
        Map<String, String> attrs = attributes == null ? Map.of() : attributes;
        synchronized (spans) {
            spans.add(new TelemetrySpan(name, attrs, now, now, null));
        }
    }

    /** 全部 span，按录制顺序。 */
    public List<TelemetrySpan> spans() {
        synchronized (spans) { return List.copyOf(spans); }
    }

    /** 仅 span 名列表，方便断言时序。 */
    public List<String> spanNames() {
        return spans().stream().map(TelemetrySpan::name).toList();
    }

    /** 打印全部 span(CLI 用)。 */
    public void prettyPrint() {
        for (TelemetrySpan s : spans()) System.out.println(s.name() + " " + s.attributes());
    }
}
