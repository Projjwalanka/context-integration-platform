package com.bank.aiassistant.controller;

import com.bank.aiassistant.model.dto.chat.ChatRequest;
import com.bank.aiassistant.model.dto.chat.ChatResponse;
import com.bank.aiassistant.model.entity.Conversation;
import com.bank.aiassistant.repository.ConversationRepository;
import com.bank.aiassistant.repository.UserRepository;
import com.bank.aiassistant.service.chat.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final ConversationRepository conversationRepository;
    private final UserRepository userRepository;

    @PostMapping
    public ResponseEntity<ChatResponse> chat(
            @Valid @RequestBody ChatRequest request,
            @AuthenticationPrincipal UserDetails principal) {
        ChatResponse response = chatService.chat(request, principal.getUsername());
        return ResponseEntity.ok(response);
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(
            @Valid @RequestBody ChatRequest request,
            @AuthenticationPrincipal UserDetails principal) {
        return chatService.chatStream(request, principal.getUsername());
    }

    @GetMapping("/conversations")
    public ResponseEntity<Page<Conversation>> listConversations(
            @AuthenticationPrincipal UserDetails principal,
            @PageableDefault(size = 20, sort = "updatedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        var userId = getUserId(principal.getUsername());
        return ResponseEntity.ok(
                conversationRepository.findByUserIdAndArchivedFalseOrderByUpdatedAtDesc(userId, pageable));
    }

    @GetMapping("/conversations/{id}")
    public ResponseEntity<Conversation> getConversation(
            @PathVariable String id,
            @AuthenticationPrincipal UserDetails principal) {
        var userId = getUserId(principal.getUsername());
        return conversationRepository.findByIdAndUserId(id, userId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/conversations/{id}")
    public ResponseEntity<Void> archiveConversation(
            @PathVariable String id,
            @AuthenticationPrincipal UserDetails principal) {
        var userId = getUserId(principal.getUsername());
        conversationRepository.findByIdAndUserId(id, userId).ifPresent(conv -> {
            conv.setArchived(true);
            conversationRepository.save(conv);
        });
        return ResponseEntity.noContent().build();
    }

    private String getUserId(String email) {
        return userRepository.findByEmail(email).map(u -> u.getId()).orElse(email);
    }
}
