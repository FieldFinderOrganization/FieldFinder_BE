package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.repository.ChatMessageRepository;
import com.example.FieldFinder.service.ChatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;


@Service
public class ChatServiceImpl implements ChatService {
    @Autowired
    private ChatMessageRepository chatMessageRepository;
}
