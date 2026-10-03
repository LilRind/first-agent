package dev.firstagent.tools;

import dev.firstagent.ToolExecutionException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DemoToolsTest {

    @Test void echoReturnsInput() {
        assertEquals("echo: {\"x\":1}", new EchoTool().execute("{\"x\":1}"));
    }

    @Test void failAlwaysThrows() {
        assertThrows(ToolExecutionException.class, () -> new FailTool().execute("{}"));
    }
}