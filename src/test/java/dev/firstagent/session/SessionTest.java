package dev.firstagent.session;

import dev.firstagent.Message;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionTest {

    @Test void exposesIdentity() {
        Session s = new Session("s1", "/cwd", Instant.parse("2026-10-05T00:00:00Z"), null);
        assertEquals("s1", s.id());
        assertEquals("/cwd", s.cwd());
        assertNull(s.name());
    }

    @Test void entriesAreAppendOnlyReadOnlyView() {
        Session s = new Session("s1", "/cwd", Instant.parse("2026-10-05T00:00:00Z"), null);
        s.append(new MessageEntry("a", null, Instant.now(), Message.user("hi")));
        s.append(new MessageEntry("b", "a", Instant.now(), Message.user("how")));
        assertEquals(2, s.entries().size());
        List<MessageEntry> view = s.entries();
        assertThrows(UnsupportedOperationException.class,
                () -> view.add(new MessageEntry("c", "b", Instant.now(), Message.user("x"))));
    }

    @Test void renameUpdatesName() {
        Session s = new Session("s1", "/cwd", Instant.now(), null);
        s.rename("my-name");
        assertEquals("my-name", s.name());
    }

    @Test void noopCompactorReturnsEmpty() {
        Session s = new Session("s1", "/cwd", Instant.now(), null);
        assertTrue(new NoopContextCompactor().maybeCompact(s).isEmpty());
    }
}