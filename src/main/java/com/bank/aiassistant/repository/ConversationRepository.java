package com.bank.aiassistant.repository;

import com.bank.aiassistant.model.entity.Conversation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ConversationRepository extends MongoRepository<Conversation, String> {

    Page<Conversation> findByUserIdAndArchivedFalseOrderByUpdatedAtDesc(String userId, Pageable pageable);

    Optional<Conversation> findByIdAndUserId(String id, String userId);

    Optional<Conversation> findById(String id);

    long countByUserIdAndArchivedFalse(String userId);
}
