package dev.firstagent.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.firstagent.Message;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 会话持久化 + 生命周期。JSONL append-only:create 排他建文件写头行,append 往末尾追加一行
 * (真 append,O(1),崩溃安全)。加载为 lenient(坏行跳过,镜像 pi "parsed without validation")。
 * 布局:baseDir/<消毒cwd>/<epochMillis>_<sessionId>.jsonl(镜像 pi 的 --<path>--/)。
 */
public final class SessionStore {
    private static final String LINE_ENDING = "\n";

    private final Path baseDir;
    private final ObjectMapper json = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    public SessionStore(Path baseDir) {
        this.baseDir = baseDir;
    }

    /** 创建会话并立即落盘(仅写头行)。 */
    public Session create(String cwd, String name) {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Session session = new Session(id, cwd, now, name);
        Path file = fileFor(session);
        try {
            Files.createDirectories(dirForCwd(cwd));
            Files.writeString(file, line(SessionHeader.of(id, cwd, now, name)) + LINE_ENDING,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new SessionIoException("create", file, e);
        }
        return session;
    }

    /** 往会话追加一条消息:更新内存日志 + 真 append 落盘一行。返回新 entry(可拿 id)。 */
    public MessageEntry append(Session session, Message message) {
        String id = newId();
        List<MessageEntry> entries = session.entries();
        String parentId = entries.isEmpty() ? null : entries.get(entries.size() - 1).id();
        MessageEntry entry = new MessageEntry(id, parentId, Instant.now(), message);
        session.append(entry);
        Path file = fileFor(session);
        try {
            Files.writeString(file, line(StoredMessageEntry.of(entry)) + LINE_ENDING,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new SessionIoException("append", file, e);
        }
        return entry;
    }

    /** 按 session id 加载整段会话历史(沿文件顺序还原线性链)。坏行跳过。 */
    public Session load(String id) {
        return read(findFile(id));
    }

    /** 列出全部会话。 */
    public List<Session> list() {
        List<Session> out = new ArrayList<>();
        for (Path f : allFiles()) {
            try {
                out.add(read(f));
            } catch (SessionIoException ignored) {
                // 跳过损坏的文件
            }
        }
        return out;
    }

    /** 取某 cwd 最近一条会话(镜像 pi findMostRecentSession)。无则空。 */
    public Optional<Session> findMostRecent(String cwd) {
        Path dir = dirForCwd(cwd);
        if (!Files.isDirectory(dir)) return Optional.empty();
        Session best = null;
        Instant bestTs = Instant.MIN;
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path f : stream.filter(p -> p.getFileName().toString().endsWith(".jsonl")).toList()) {
                try {
                    Session candidate = read(f);
                    if (best == null || candidate.createdAt().isAfter(bestTs)) {
                        best = candidate;
                        bestTs = candidate.createdAt();
                    }
                } catch (SessionIoException ignored) {
                    // 跳过损坏文件
                }
            }
        } catch (IOException e) {
            throw new SessionIoException("findMostRecent", dir, e);
        }
        return Optional.ofNullable(best);
    }

    /** 删除会话文件。 */
    public void delete(String id) {
        Path file = findFile(id);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new SessionIoException("delete", file, e);
        }
    }

    /** 重命名会话:改头行 name,整体重写文件(header 是首行,改名必须重写)。 */
    public void rename(String id, String name) {
        Path file = findFile(id);
        Session s = read(file);
        s.rename(name);
        List<String> out = new ArrayList<>();
        out.add(line(SessionHeader.of(s.id(), s.cwd(), s.createdAt(), s.name())));
        for (MessageEntry e : s.entries()) {
            out.add(line(StoredMessageEntry.of(e)));
        }
        try {
            Files.writeString(file, String.join(LINE_ENDING, out) + LINE_ENDING,
                    StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new SessionIoException("rename", file, e);
        }
    }

    // --- 内部 ---

    private Session read(Path file) {
        SessionHeader header = null;
        List<MessageEntry> entries = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) continue;
                try {
                    JsonNode node = json.readTree(line);
                    String type = node.path("type").asText();
                    if ("session".equals(type)) {
                        header = json.treeToValue(node, SessionHeader.class);
                    } else if (MessageEntry.TYPE.equals(type)) {
                        entries.add(json.treeToValue(node, StoredMessageEntry.class).toMessageEntry());
                    }
                    // 未知 type / 坏行 → 跳过(lenient;JsonProcessingException 是检查型 IOException)
                } catch (Exception ignored) {
                    // skip malformed line
                }
            }
        } catch (IOException e) {
            throw new SessionIoException("read", file, e);
        }
        if (header == null) {
            throw new SessionIoException("missing header", file, null);
        }
        Session s = new Session(header.id(), header.cwd(), header.createdAt(), header.name());
        for (MessageEntry e : entries) s.append(e);
        return s;
    }

    private Path findFile(String id) {
        for (Path f : allFiles()) {
            if (f.getFileName().toString().endsWith("_" + id + ".jsonl")) return f;
        }
        throw new SessionIoException("session not found: " + id, null, null);
    }

    private Path fileFor(Session s) {
        String fn = s.createdAt().toEpochMilli() + "_" + s.id() + ".jsonl";
        return dirForCwd(s.cwd()).resolve(fn);
    }

    private Path dirForCwd(String cwd) {
        return baseDir.resolve(sanitize(cwd));
    }

    private List<Path> allFiles() {
        if (!Files.isDirectory(baseDir)) return List.of();
        try (Stream<Path> stream = Files.walk(baseDir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .toList();
        } catch (IOException e) {
            throw new SessionIoException("walk", baseDir, e);
        }
    }

    private String sanitize(String cwd) {
        return cwd.replaceAll("[/\\\\: ]+", "-").replaceFirst("^[-]+", "");
    }

    private String line(Object dto) {
        try {
            return json.writeValueAsString(dto);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /** 消息 entry 的落盘 DTO:type/id/parentId/timestamp + 嵌套 message。 */
    record StoredMessageEntry(String type, String id, String parentId, String timestamp,
                              StoredMessage message) {
        static StoredMessageEntry of(MessageEntry e) {
            return new StoredMessageEntry(MessageEntry.TYPE, e.id(), e.parentId(),
                    e.timestamp().toString(), StoredMessage.from(e.message()));
        }

        MessageEntry toMessageEntry() {
            return new MessageEntry(id, parentId, timestamp != null ? Instant.parse(timestamp) : null,
                    message.toMessage());
        }
    }
}