package dev.firstagent.session;

import dev.firstagent.Message;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SessionProjectorTest {

    private static Session sessionWith(Message... msgs) {
        Session s = new Session("s1", "/cwd", Instant.now(), null);
        String parent = null;
        for (Message m : msgs) {
            String id = "e" + s.entries().size();
            s.append(new MessageEntry(id, parent, Instant.now(), m));
            parent = id;
        }
        return s;
    }

    @Test void projectPassthroughsAllMessagesInOrder() {
        Session s = sessionWith(Message.user("hi"), Message.assistant("hello", List.of()));
        List<Message> out = new SessionProjector().project(s);
        assertEquals(2, out.size());
        assertEquals("hi", out.get(0).text());
        assertEquals("hello", out.get(1).text());
    }

    @Test void defaultCompactorIsNoop() {
        Session s = sessionWith(Message.user("hi"), Message.assistant("hello", List.of()));
        assertEquals(2, new SessionProjector().project(s).size());
    }

    @Test void injectedCompactorTrimsHistory() {
        Session s = sessionWith(Message.user("hi"), Message.user("kep"), Message.user("latest"));
        ContextCompactor trimmer = session -> Optional.of(List.of(session.entries().get(2).message()));
        List<Message> out = new SessionProjector(trimmer).project(s);
        assertEquals(1, out.size());
        assertEquals("latest", out.get(0).text());
    }
}