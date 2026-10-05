package dev.firstagent.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class GrepToolTest {
    @TempDir Path tmp;

    private void seed() throws Exception {
        Files.writeString(tmp.resolve("a.java"), "class A {\n int x = 1;\n}\n", StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("b.txt"), "hello world\nfoo bar\n", StandardCharsets.UTF_8);
    }

    @Test void findsMatchingLines() throws Exception {
        seed();
        GrepTool tool = new GrepTool(tmp.toString());
        String out = tool.execute("{\"pattern\":\"class\"}");
        assertTrue(out.contains("a.java:1"), out);
    }

    @Test void ignoreCase() throws Exception {
        seed();
        GrepTool tool = new GrepTool(tmp.toString());
        String out = tool.execute("{\"pattern\":\"HELLO\",\"ignoreCase\":true}");
        assertTrue(out.contains("hello"), out);
    }

    @Test void noMatchReturnsMessage() throws Exception {
        seed();
        GrepTool tool = new GrepTool(tmp.toString());
        String out = tool.execute("{\"pattern\":\"zzzznope\"}");
        assertTrue(out.contains("无匹配"), out);
    }

    @Test void globFiltersFiles() throws Exception {
        seed();
        GrepTool tool = new GrepTool(tmp.toString());
        String out = tool.execute("{\"pattern\":\"x\",\"glob\":\"*.java\"}");
        assertTrue(out.contains("a.java"), out);
        assertFalse(out.contains("b.txt"), out);
    }
}