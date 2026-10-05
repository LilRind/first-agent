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

    @Test void globMatchesNestedFiles() throws Exception {
        Files.createDirectories(tmp.resolve("sub"));
        Files.writeString(tmp.resolve("sub/a.java"), "class N {}\n", StandardCharsets.UTF_8);
        GrepTool tool = new GrepTool(tmp.toString());
        String out = tool.execute("{\"pattern\":\"class\",\"glob\":\"*.java\"}");
        assertTrue(out.contains("sub/a.java"), out);
    }

    @Test void limitStopsAtMax() throws Exception {
        Files.writeString(tmp.resolve("n.txt"), "alpha\nbeta\nskip\n", StandardCharsets.UTF_8);
        GrepTool tool = new GrepTool(tmp.toString());
        String out = tool.execute("{\"pattern\":\"(alpha|beta)\",\"limit\":2}");
        int matches = 0;
        for (String line : out.split("\\R")) if (line.contains(": ")) matches++;
        assertEquals(2, matches, out);
        assertFalse(out.contains("[达到匹配上限"), out);
    }

    @Test void limitHitShowsHint() throws Exception {
        Files.writeString(tmp.resolve("n.txt"), "alpha\nbeta\ngamma\n", StandardCharsets.UTF_8);
        GrepTool tool = new GrepTool(tmp.toString());
        String out = tool.execute("{\"pattern\":\"(alpha|beta|gamma)\",\"limit\":2}");
        int matches = 0;
        for (String line : out.split("\\R")) if (line.contains(": ")) matches++;
        assertEquals(2, matches, out);
        assertTrue(out.contains("[达到匹配上限"), out);
    }

    @Test void contextShowsSurroundingLines() throws Exception {
        Files.writeString(tmp.resolve("c.txt"), "one\ntwo\nthree\nfour\n", StandardCharsets.UTF_8);
        GrepTool tool = new GrepTool(tmp.toString());
        String out = tool.execute("{\"pattern\":\"three\",\"context\":1}");
        assertTrue(out.contains("-2: two"), out);
        assertTrue(out.contains(":3: three"), out);
        assertTrue(out.contains("-4: four"), out);
    }

    @Test void multipleMatchesInOneFile() throws Exception {
        Files.writeString(tmp.resolve("m.txt"), "match1\nskip\nmatch2\n", StandardCharsets.UTF_8);
        GrepTool tool = new GrepTool(tmp.toString());
        String out = tool.execute("{\"pattern\":\"match[12]\"}");
        assertTrue(out.contains("m.txt:1"), out);
        assertTrue(out.contains("m.txt:3"), out);
    }
}