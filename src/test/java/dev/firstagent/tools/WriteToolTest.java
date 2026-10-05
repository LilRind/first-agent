package dev.firstagent.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class WriteToolTest {
    @TempDir Path tmp;

    @Test void writesAndOverwrites() throws Exception {
        WriteTool tool = new WriteTool(tmp.toString());
        String r = tool.execute("{\"path\":\"a.txt\",\"content\":\"hello\"}");
        assertEquals("hello", Files.readString(tmp.resolve("a.txt"), StandardCharsets.UTF_8));
        tool.execute("{\"path\":\"a.txt\",\"content\":\"world\"}");
        assertEquals("world", Files.readString(tmp.resolve("a.txt"), StandardCharsets.UTF_8));
    }

    @Test void createsParentDirs() throws Exception {
        WriteTool tool = new WriteTool(tmp.toString());
        tool.execute("{\"path\":\"sub/deep/b.txt\",\"content\":\"x\"}");
        assertTrue(Files.isRegularFile(tmp.resolve("sub/deep/b.txt")));
    }

    @Test void missingContentThrows() {
        WriteTool tool = new WriteTool(tmp.toString());
        assertThrows(dev.firstagent.ToolExecutionException.class,
            () -> tool.execute("{\"path\":\"a.txt\"}"));
    }

    @Test void missingPathThrows() {
        WriteTool tool = new WriteTool(tmp.toString());
        assertThrows(dev.firstagent.ToolExecutionException.class,
            () -> tool.execute("{\"content\":\"x\"}"));
    }

    @Test void blankPathThrows() {
        WriteTool tool = new WriteTool(tmp.toString());
        assertThrows(dev.firstagent.ToolExecutionException.class,
            () -> tool.execute("{\"path\":\"   \",\"content\":\"x\"}"));
    }
}
