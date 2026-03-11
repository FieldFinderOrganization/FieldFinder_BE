package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.entity.ChatMessage;
import com.example.FieldFinder.repository.ChatMessageRepository;
import com.example.FieldFinder.service.ChatService;
import com.google.api.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.awt.print.Pageable;

@Service
public class ChatServiceImpl implements ChatService {
    @Autowired
    private ChatMessageRepository chatMessageRepository;
}
