package com.example.FieldFinder.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.Date;
import java.util.UUID;

@Entity
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Getter
@Setter
@Table(name = "chat_messages")
public class ChatMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "message_id", updatable = false, nullable = false)
    private UUID messageId;

    @Column(name = "sender_id", nullable = false)
    private UUID senderId;

    @Column(name = "receiver_id", nullable = false)
    private UUID receiverId;

    @Column(name = "content")
    private String content;

    @Column(name = "image_url")
    private String imageUrl;

    @Column(name = "timestamp")
    private Date timestamp;

    @Column(name = "is_read")
    @Builder.Default
    private Boolean isRead = false;

    @Column(name = "message_type")
    @Builder.Default
    private String type = "TEXT";

}
