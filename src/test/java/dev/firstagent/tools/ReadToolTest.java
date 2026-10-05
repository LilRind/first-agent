package dev.firstagent.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ReadToolTest {
    @TempDir Path tmp;

    private Path writeFile(String content) throws Exception {
        Path f = tmp.resolve("sample.txt");
        Files.writeString(f, content, StandardCharsets.UTF_8);
        return f;
    }

    @Test void readsWholeFile() throws Exception {
        writeFile("line1\nline2\nline3\n");
        ReadTool tool = new ReadTool(tmp.toString());
        String out = tool.execute("{\"path\":\"sample.txt\"}");
        assertEquals("line1\nline2\nline3", out);
    }

    @Test void respectsLimit() throws Exception {
        writeFile(String.join("\n", List.of("a", "b", "c", "d", "e")));
        ReadTool tool = new ReadTool(tmp.toString());
        String out = tool.execute("{\"path\":\"sample.txt\",\"limit\":2}");
        assertEquals("a\nb", out);
        assertFalse(out.contains("c"), out);
    }

    @Test void offsetStartsMidFile() throws Exception {
        writeFile(String.join("\n", List.of("a", "b", "c", "d", "e")));
        ReadTool tool = new ReadTool(tmp.toString());
        String out = tool.execute("{\"path\":\"sample.txt\",\"offset\":3}");
        assertEquals("c\nd\ne", out);
    }

    @Test void offsetAtEndReadsLastLine() throws Exception {
        writeFile(String.join("\n", List.of("a", "b", "c", "d", "e")));
        ReadTool tool = new ReadTool(tmp.toString());
        String out = tool.execute("{\"path\":\"sample.txt\",\"offset\":5}");
        assertEquals("e", out);
    }

    @Test void offsetBeyondEndThrows() throws Exception {
        writeFile(String.join("\n", List.of("a", "b", "c", "d", "e")));
        ReadTool tool = new ReadTool(tmp.toString());
        assertThrows(dev.firstagent.ToolExecutionException.class,
            () -> tool.execute("{\"path\":\"sample.txt\",\"offset\":6}"));
    }

    @Test void negativeLimitReadsAll() throws Exception {
        writeFile(String.join("\n", List.of("a", "b", "c", "d", "e")));
        ReadTool tool = new ReadTool(tmp.toString());
        String out = tool.execute("{\"path\":\"sample.txt\",\"limit\":-1}");
        assertEquals("a\nb\nc\nd\ne", out);
    }

    @Test void missingPathThrows() {
        ReadTool tool = new ReadTool(tmp.toString());
        assertThrows(dev.firstagent.ToolExecutionException.class, () -> tool.execute("{}"));
    }

    @Test void missingFileThrows() {
        ReadTool tool = new ReadTool(tmp.toString());
        assertThrows(dev.firstagent.ToolExecutionException.class,
            () -> tool.execute("{\"path\":\"nope.txt\"}"));
    }
}