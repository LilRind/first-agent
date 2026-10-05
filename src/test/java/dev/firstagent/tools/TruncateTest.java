package dev.firstagent.tools;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TruncateTest {

    @Test void keepsShortText() {
        assertEquals("a\nb", Truncate.truncate("a\nb", 2000, 16 * 1024));
    }

    @Test void truncatesByLines() {
        String out = Truncate.truncate("1\n2\n3\n4", 2, 16 * 1024);
        assertTrue(out.contains("1") && out.contains("2"), out);
        assertFalse(out.contains("3"), out);
        assertTrue(out.contains("more lines"), out);
    }

    @Test void truncatesByBytes() {
        String out = Truncate.truncate("abc", 2000, 2);
        assertTrue(out.contains("bytes"), out);
    }
}
