package com.roadguard.service;

import com.roadguard.domain.ChatMessage;
import com.roadguard.domain.ServiceRequest;
import com.roadguard.domain.User;
import com.roadguard.domain.enums.RequestStatus;
import com.roadguard.domain.enums.Role;
import com.roadguard.repository.ChatMessageRepository;
import com.roadguard.repository.ServiceRequestRepository;
import com.roadguard.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ChatService {

    public static final int MAX_TEXT = 2000;
    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;

    private static final Pattern DATA_URL =
            Pattern.compile("^data:image/(jpeg|png|webp);base64,([A-Za-z0-9+/=]+)$");

    private static final Map<String, String> EXTENSIONS = Map.of(
            "jpeg", ".jpg",
            "png", ".png",
            "webp", ".webp");

    private final ChatMessageRepository messages;
    private final ServiceRequestRepository requests;

    @Value("${app.uploads.dir:uploads}")
    private String uploadsDir;

    public record Line(
            Long id,
            Long requestId,
            String sender,
            String senderRole,
            String text,
            String mediaUrl,
            Instant sentAt) {
    }

    public static boolean isOpen(ServiceRequest request) {
        return request.getAssignedMechanic() != null
                && request.getStatus().isAssignedToMechanic();
    }

    @Transactional
    public Line record(Long requestId, User sender, String rawText, String mediaDataUrl) {
        ServiceRequest request = requests.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request not found"));

        if (!isOpen(request)) {
            throw new IllegalArgumentException("Chat opens once a mechanic accepts the job");
        }

        boolean isDriver = request.getDriver().getId().equals(sender.getId());
        boolean isMechanic = request.getAssignedMechanic() != null
                && request.getAssignedMechanic().getId().equals(sender.getId());
        if (!isDriver && !isMechanic) {
            throw new AccessDeniedException("You are not part of this conversation");
        }

        String text = rawText == null ? "" : rawText.trim();
        if (text.length() > MAX_TEXT) {
            throw new IllegalArgumentException("That message is too long");
        }

        String storedName = storeImage(requestId, mediaDataUrl);
        if (text.isBlank() && storedName == null) {
            throw new IllegalArgumentException("Nothing to send");
        }

        ChatMessage message = new ChatMessage(request, sender, isDriver ? "DRIVER" : "MECHANIC");
        message.setText(text.isBlank() ? null : text);
        message.setMediaPath(storedName);
        messages.save(message);

        return toLine(message);
    }

    @Transactional(readOnly = true)
    public List<Line> conversation(AuthUser caller, Long requestId) {
        ServiceRequest request = requests.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request not found"));

        if (!canRead(caller, request)) {
            throw new AccessDeniedException("This conversation is not yours");
        }
        return messages.findConversation(requestId).stream().map(ChatService::toLine).toList();
    }

    public byte[] image(AuthUser caller, Long requestId, String name) {
        ServiceRequest request = requests.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request not found"));

        if (!canRead(caller, request)) {
            throw new AccessDeniedException("This conversation is not yours");
        }

        Path file = UploadRules.within(chatDirectory(), name);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("No such image");
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read that image");
        }
    }

    private boolean canRead(AuthUser caller, ServiceRequest request) {
        if (caller.getRole() == Role.ADMIN) {
            return true;
        }
        if (request.getDriver().getId().equals(caller.getId())) {
            return true;
        }
        return request.getAssignedMechanic() != null
                && request.getAssignedMechanic().getId().equals(caller.getId());
    }

    private String storeImage(Long requestId, String dataUrl) {
        if (dataUrl == null || dataUrl.isBlank()) {
            return null;
        }

        Matcher matcher = DATA_URL.matcher(dataUrl);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Only JPG, PNG and WEBP images can be sent");
        }

        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(matcher.group(2));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("That image could not be read");
        }
        if (bytes.length > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("That image is larger than 5 MB");
        }

        String name = requestId + "-" + UUID.randomUUID() + EXTENSIONS.get(matcher.group(1));
        Path target = UploadRules.within(chatDirectory(), name);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException e) {
            throw new IllegalStateException("Could not keep that image");
        }
        return name;
    }

    private Path chatDirectory() {
        return Paths.get(uploadsDir, "chat").toAbsolutePath().normalize();
    }

    private static Line toLine(ChatMessage message) {
        return new Line(
                message.getId(),
                message.getRequest().getId(),
                message.getSender().getUsername(),
                message.getSenderRole(),
                message.getText(),
                message.getMediaPath() == null
                        ? null
                        : "/api/requests/" + message.getRequest().getId()
                                + "/chat/media/" + message.getMediaPath(),
                message.getSentAt());
    }
}
