package com.roadguard.service;

import com.roadguard.domain.ServiceRequest;
import com.roadguard.domain.User;
import com.roadguard.domain.enums.IssueType;
import com.roadguard.domain.enums.RequestStatus;
import com.roadguard.domain.enums.Role;
import com.roadguard.repository.ServiceRequestRepository;
import com.roadguard.repository.UserRepository;
import com.roadguard.security.AuthUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class ChatTest {

    private static final AtomicInteger UNIQUE = new AtomicInteger();

    @Autowired ChatService chat;
    @Autowired ServiceRequestRepository requests;
    @Autowired UserRepository users;
    @Autowired TransactionTemplate tx;

    private record Job(Long requestId, User driver, User mechanic, User stranger) {
    }

    private Job acceptedJob(RequestStatus status) {
        return tx.execute(s -> {
            int n = UNIQUE.incrementAndGet();
            User driver = users.save(new User("ch_d_" + n, "ch_d_" + n + "@t.com", "x", Role.DRIVER));
            User mech = users.save(new User("ch_m_" + n, "ch_m_" + n + "@t.com", "x", Role.MECHANIC));
            User other = users.save(new User("ch_x_" + n, "ch_x_" + n + "@t.com", "x", Role.DRIVER));

            ServiceRequest r = new ServiceRequest(driver, IssueType.FLAT_TIRE, 23.81, 90.41, "chat");
            r.setSearchRadiusKm(5);
            if (status.isAssignedToMechanic()) {
                r.setAssignedMechanic(mech);
            }
            r.setStatus(status);
            requests.save(r);

            return new Job(r.getId(), driver, mech, other);
        });
    }

    private AuthUser as(User user) {
        return new AuthUser(user);
    }

    @Test
    @DisplayName("a message survives being sent, which is the whole point")
    void messageIsKept() {
        Job job = acceptedJob(RequestStatus.ACCEPTED);

        chat.record(job.requestId(), job.driver(), "I am by the blue gate", null);

        List<ChatService.Line> said = chat.conversation(as(job.driver()), job.requestId());
        assertEquals(1, said.size());
        assertEquals("I am by the blue gate", said.get(0).text());
        assertEquals("DRIVER", said.get(0).senderRole());
        assertNotNull(said.get(0).id());
    }

    @Test
    @DisplayName("both sides see the same conversation, in order")
    void bothSidesSeeTheSameThread() {
        Job job = acceptedJob(RequestStatus.EN_ROUTE);

        chat.record(job.requestId(), job.driver(), "how long?", null);
        chat.record(job.requestId(), job.mechanic(), "ten minutes", null);
        chat.record(job.requestId(), job.driver(), "thank you", null);

        List<ChatService.Line> driverView = chat.conversation(as(job.driver()), job.requestId());
        List<ChatService.Line> mechanicView = chat.conversation(as(job.mechanic()), job.requestId());

        assertEquals(3, driverView.size());
        assertEquals(driverView.size(), mechanicView.size());
        assertEquals("how long?", driverView.get(0).text());
        assertEquals("ten minutes", driverView.get(1).text());
        assertEquals("thank you", driverView.get(2).text());
        assertEquals("MECHANIC", driverView.get(1).senderRole());
    }

    @Test
    @DisplayName("nobody outside the job can read the conversation")
    void strangersAreRefused() {
        Job job = acceptedJob(RequestStatus.ACCEPTED);
        chat.record(job.requestId(), job.driver(), "private", null);

        assertThrows(AccessDeniedException.class,
                () -> chat.conversation(as(job.stranger()), job.requestId()));
    }

    @Test
    @DisplayName("nobody outside the job can write into it")
    void strangersCannotSend() {
        Job job = acceptedJob(RequestStatus.ACCEPTED);

        assertThrows(AccessDeniedException.class,
                () -> chat.record(job.requestId(), job.stranger(), "let me in", null));
    }

    @Test
    @DisplayName("chat does not open before a mechanic has accepted")
    void closedBeforeAccept() {
        Job job = acceptedJob(RequestStatus.SEARCHING);

        assertThrows(IllegalArgumentException.class,
                () -> chat.record(job.requestId(), job.driver(), "anyone there?", null));
    }

    @Test
    @DisplayName("chat closes once the job is finished")
    void closedAfterCompletion() {
        Job job = acceptedJob(RequestStatus.COMPLETED);

        assertThrows(IllegalArgumentException.class,
                () -> chat.record(job.requestId(), job.driver(), "one more thing", null));
    }

    @Test
    @DisplayName("an empty message is refused rather than stored")
    void emptyMessageIsRefused() {
        Job job = acceptedJob(RequestStatus.ACCEPTED);

        assertThrows(IllegalArgumentException.class,
                () -> chat.record(job.requestId(), job.driver(), "   ", null));
    }

    @Test
    @DisplayName("an over-long message is refused")
    void tooLongIsRefused() {
        Job job = acceptedJob(RequestStatus.ACCEPTED);
        String tooMuch = "x".repeat(ChatService.MAX_TEXT + 1);

        assertThrows(IllegalArgumentException.class,
                () -> chat.record(job.requestId(), job.driver(), tooMuch, null));
    }

    @Test
    @DisplayName("something that is not an image is refused")
    void nonImageAttachmentIsRefused() {
        Job job = acceptedJob(RequestStatus.ACCEPTED);

        assertThrows(IllegalArgumentException.class,
                () -> chat.record(job.requestId(), job.driver(), "look",
                        "data:text/html;base64,PHNjcmlwdD4="));
    }

    @Test
    @DisplayName("an image is kept as a file and served by url, not stuffed into the row")
    void imageBecomesAUrl() {
        Job job = acceptedJob(RequestStatus.ARRIVED);
        String onePixelPng = "data:image/png;base64,"
                + "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

        ChatService.Line line = chat.record(job.requestId(), job.mechanic(), null, onePixelPng);

        assertNull(line.text());
        assertNotNull(line.mediaUrl());
        assertTrue(line.mediaUrl().startsWith("/api/requests/" + job.requestId() + "/chat/media/"),
                "the image is fetched by url: " + line.mediaUrl());
        assertTrue(line.mediaUrl().endsWith(".png"));

        byte[] bytes = chat.image(as(job.driver()), job.requestId(),
                line.mediaUrl().substring(line.mediaUrl().lastIndexOf('/') + 1));
        assertTrue(bytes.length > 0);
    }

    @Test
    @DisplayName("a stranger cannot fetch an image from someone else's conversation")
    void strangersCannotFetchImages() {
        Job job = acceptedJob(RequestStatus.ARRIVED);
        String onePixelPng = "data:image/png;base64,"
                + "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";
        ChatService.Line line = chat.record(job.requestId(), job.driver(), null, onePixelPng);
        String name = line.mediaUrl().substring(line.mediaUrl().lastIndexOf('/') + 1);

        assertThrows(AccessDeniedException.class,
                () -> chat.image(as(job.stranger()), job.requestId(), name));
    }

    @Test
    @DisplayName("a name that climbs out of the chat folder is refused")
    void pathTraversalIsRefused() {
        Job job = acceptedJob(RequestStatus.ARRIVED);

        assertThrows(IllegalArgumentException.class,
                () -> chat.image(as(job.driver()), job.requestId(), "../../secrets.txt"));
    }
}
