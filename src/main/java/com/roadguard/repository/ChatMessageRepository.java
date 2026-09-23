package com.roadguard.repository;

import com.roadguard.domain.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    @Query("""
            select m from ChatMessage m
              join fetch m.sender
             where m.request.id = :requestId
             order by m.sentAt asc, m.id asc
            """)
    List<ChatMessage> findConversation(Long requestId);
}
