package dev.firstagent.session;

import dev.firstagent.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionStoreTest {
    @TempDir Path tmp;

    @Test void createPersistsHeaderLine() throws Exception {
        SessionStore store = new SessionStore(tmp.resolve("sessions"));
        store.create("/repo", "demo");
        Path file = findOnlyFile(tmp.resolve("sessions"));
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(content.startsWith("{\"type\":\"session\",\"version\":3"));
        assertTrue(content.contains("\"cwd\":\"/repo\""));
    }

    @Test void loadRoundTripsAppendedMessages() throws Exception {
        SessionStore store = new SessionStore(tmp.resolve("sessions"));
        Session s = store.create("/repo", null);
        store.append(s, Message.user("hi"));
        store.append(s, Message.assistant("hello", List.of()));
        Session loaded = store.load(s.id());
        assertEquals(s.id(), loaded.id());
        assertEquals("/repo", loaded.cwd());
        assertEquals(2, loaded.entries().size());
        assertEquals("hi", loaded.entries().get(0).message().text());
        assertEquals("hello", loaded.entries().get(1).message().text());
    }

    @Test void appendOnlyOneLineAddedAndOldBytesKept() throws Exception {
        SessionStore store = new SessionStore(tmp.resolve("sessions"));
        Session s = store.create("/repo", null);
        store.append(s, Message.user("hi"));
        Path file = findOnlyFile(tmp.resolve("sessions"));
        String afterFirst = Files.readString(file, StandardCharsets.UTF_8);
        store.append(s, Message.user("second"));
        String afterSecond = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(afterSecond.startsWith(afterFirst));
        assertEquals(3, Files.readAllLines(file).size());
    }

    @Test void lenientLoadSkipsMalformedLine() throws Exception {
        SessionStore store = new SessionStore(tmp.resolve("sessions"));
        Session s = store.create("/repo", null);
        store.append(s, Message.user("ok"));
        Path file = findOnlyFile(tmp.resolve("sessions"));
        Files.writeString(file, "this is not json\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        Session loaded = store.load(s.id());
        assertEquals(1, loaded.entries().size());
        assertEquals("ok", loaded.entries().get(0).message().text());
    }

    private Path findOnlyFile(Path root) throws Exception {
        try (var stream = Files.walk(root)) {
            var list = stream.filter(Files::isRegularFile).toList();
            assertEquals(1, list.size(), "expected exactly one session file");
            return list.get(0);
        }
    }
}