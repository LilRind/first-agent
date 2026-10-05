package dev.firstagent.tools;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class PathUtilTest {

    @Test void absolutePathIsUsedAsIs() {
        Path p = PathUtil.resolveToCwd("C:/x/y.txt", "/cwd");
        assertTrue(p.isAbsolute());
    }

    @Test void relativeResolvesToCwd() {
        Path p = PathUtil.resolveToCwd("x/y.txt", "C:/base");
        assertEquals(Path.of("C:/base/x/y.txt").normalize(), p);
    }
}
