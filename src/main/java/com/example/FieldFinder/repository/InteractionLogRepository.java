package com.example.FieldFinder.repository;

import com.example.FieldFinder.entity.log.InteractionLog;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface InteractionLogRepository extends MongoRepository<InteractionLog, String> {
}
