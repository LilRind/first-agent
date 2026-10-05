package dev.firstagent.tools;

import java.nio.file.Path;

/** 把模型给的路径解析成绝对路径：~ 展开 / 绝对直用 / 相对拼 cwd。 */
public final class PathUtil {
    private PathUtil() {}

    public static Path resolveToCwd(String path, String cwd) {
        String p = path == null ? "" : path.strip();
        if (p.equals("~") || p.startsWith("~/")) {
            p = System.getProperty("user.home") + p.substring(1);
        }
        Path base = Path.of(p);
        if (base.isAbsolute()) return base.normalize();
        return Path.of(cwd).resolve(p).normalize();
    }
}
