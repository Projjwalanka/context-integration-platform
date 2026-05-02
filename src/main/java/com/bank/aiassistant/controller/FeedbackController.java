package com.bank.aiassistant.controller;

import com.bank.aiassistant.model.dto.chat.ChatResponse;
import com.bank.aiassistant.model.entity.Feedback;
import com.bank.aiassistant.repository.FeedbackRepository;
import com.bank.aiassistant.repository.UserRepository;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/feedback")
@RequiredArgsConstructor
public class FeedbackController {

    private final FeedbackRepository feedbackRepository;
    private final UserRepository userRepository;

    record FeedbackRequest(
            @NotBlank String messageId,
            @NotBlank String conversationId,
            @NotBlank String type,
            @Min(1) @Max(5) Integer rating,
            String comment,
            String category
    ) {}

    @PostMapping
    public ResponseEntity<Feedback> submitFeedback(
            @RequestBody FeedbackRequest request,
            @AuthenticationPrincipal UserDetails principal) {
        var user = userRepository.findByEmail(principal.getUsername()).orElseThrow();
        Feedback feedback = Feedback.builder()
                .messageId(request.messageId())
                .conversationId(request.conversationId())
                .userId(user.getId())
                .type(Feedback.FeedbackType.valueOf(request.type()))
                .rating(request.rating())
                .comment(request.comment())
                .category(request.category())
                .build();
        return ResponseEntity.status(HttpStatus.CREATED).body(feedbackRepository.save(feedback));
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        Double avgRating = feedbackRepository.averageRating();
        return ResponseEntity.ok(Map.of(
                "averageRating", avgRating != null ? avgRating : 0.0,
                "totalFeedback", feedbackRepository.count()
        ));
    }
}
