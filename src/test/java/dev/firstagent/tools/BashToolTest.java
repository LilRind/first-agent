package dev.firstagent.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class BashToolTest {
    @TempDir Path tmp;

    @Test void runsCommandAndCapturesOutput() {
        BashTool tool = new BashTool(tmp.toString());
        String out = tool.execute("{\"command\":\"echo hello\"}");
        assertTrue(out.contains("hello"), out);
        assertTrue(out.contains("exit=0"), out);
    }

    @Test void reportsNonZeroExit() {
        BashTool tool = new BashTool(tmp.toString());
        String out = tool.execute("{\"command\":\"exit 3\"}");
        assertTrue(out.contains("exit=3"), out);
    }

    @Test void capturesStderrThroughMergedStream() {
        BashTool tool = new BashTool(tmp.toString());
        String out = tool.execute("{\"command\":\"echo err 1>&2\"}");
        assertTrue(out.contains("err"), out);
    }

    @Test void missingCommandThrows() {
        BashTool tool = new BashTool(tmp.toString());
        assertThrows(dev.firstagent.ToolExecutionException.class, () -> tool.execute("{}"));
    }
}
