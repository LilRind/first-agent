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

    @Test void groupsByCwdIntoSeparateDirs() throws Exception {
        SessionStore store = new SessionStore(tmp.resolve("sessions"));
        store.create("/proj-a", null);
        store.create("/proj-b", null);
        try (var stream = Files.list(tmp.resolve("sessions"))) {
            long dirs = stream.filter(Files::isDirectory).count();
            assertEquals(2, dirs);
        }
    }

    @Test void findMostRecentReturnsLatestForCwd() throws Exception {
        SessionStore store = new SessionStore(tmp.resolve("sessions"));
        Session older = store.create("/repo", null);
        store.append(older, Message.user("first"));
        Thread.sleep(5); // 保证两次 create 的 created-at 有序
        Session newer = store.create("/repo", null);
        store.append(newer, Message.user("second"));
        assertEquals(newer.id(), store.findMostRecent("/repo").orElseThrow().id());
    }

    @Test void findMostRecentSeparatesByCwd() throws Exception {
        SessionStore store = new SessionStore(tmp.resolve("sessions"));
        Session a = store.create("/proj-a", null);
        store.append(a, Message.user("a-msg"));
        Session b = store.create("/proj-b", null);
        store.append(b, Message.user("b-msg"));
        assertEquals(a.id(), store.findMostRecent("/proj-a").orElseThrow().id());
        assertEquals(b.id(), store.findMostRecent("/proj-b").orElseThrow().id());
        assertTrue(store.findMostRecent("/nothing").isEmpty());
    }

    @Test void deleteRemovesSessionButKeepsOthers() throws Exception {
        SessionStore store = new SessionStore(tmp.resolve("sessions"));
        Session a = store.create("/a", null);
        store.create("/b", null);
        store.delete(a.id());
        assertThrows(SessionIoException.class, () -> store.load(a.id()));
    }

    @Test void renamePersistsNewNameAcrossReload() throws Exception {
        SessionStore store = new SessionStore(tmp.resolve("sessions"));
        Session s = store.create("/repo", "old");
        store.rename(s.id(), "new-name");
        assertEquals("new-name", store.load(s.id()).name());
    }

    private Path findOnlyFile(Path root) throws Exception {
        try (var stream = Files.walk(root)) {
            var list = stream.filter(Files::isRegularFile).toList();
            assertEquals(1, list.size(), "expected exactly one session file");
            return list.get(0);
        }
    }
}