package com.roadguard.web;

import com.roadguard.repository.UserRepository;
import com.roadguard.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.Map;

@Controller
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chat;
    private final UserRepository users;
    private final SimpMessagingTemplate messaging;

    public record Incoming(Long requestId, String text, String mediaDataUrl) {
    }

    @MessageMapping("/chat.send")
    public void send(@Payload Incoming incoming, Principal principal) {
        if (incoming == null || incoming.requestId() == null || principal == null) {
            return;
        }

        var sender = users.findByUsernameIgnoreCase(principal.getName())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        ChatService.Line line = chat.record(
                incoming.requestId(), sender, incoming.text(), incoming.mediaDataUrl());

        messaging.convertAndSend("/topic/chat/" + incoming.requestId(), line);
    }

    @MessageExceptionHandler
    public void chatError(Exception error, Principal principal) {
        if (principal == null) {
            return;
        }
        messaging.convertAndSendToUser(principal.getName(), "/queue/updates", Map.of(
                "type", "CHAT_ERROR",
                "message", error.getMessage() == null
                        ? "That message could not be sent"
                        : error.getMessage()));
    }
}
