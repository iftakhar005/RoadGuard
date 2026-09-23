package com.roadguard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "chat_messages", indexes = @Index(name = "idx_chat_request", columnList = "request_id"))
@Getter
@Setter
@NoArgsConstructor
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "request_id", nullable = false)
    private ServiceRequest request;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_id", nullable = false)
    private User sender;

    @Column(nullable = false, length = 20)
    private String senderRole;

    @Column(length = 2000)
    private String text;

    @Column(length = 255)
    private String mediaPath;

    @Column(nullable = false, updatable = false)
    private Instant sentAt = Instant.now();

    public ChatMessage(ServiceRequest request, User sender, String senderRole) {
        this.request = request;
        this.sender = sender;
        this.senderRole = senderRole;
    }
}
