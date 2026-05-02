package com.bank.aiassistant.repository;

import com.bank.aiassistant.model.entity.Feedback;
import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FeedbackRepository extends MongoRepository<Feedback, String> {

    List<Feedback> findByConversationId(String conversationId);

    @Aggregation(pipeline = {
            "{ $group: { _id: '$category', count: { $sum: 1 } } }"
    })
    List<CategoryCount> countByCategory();

    @Aggregation(pipeline = {
            "{ $match: { rating: { $ne: null } } }",
            "{ $group: { _id: null, avg: { $avg: '$rating' } } }"
    })
    List<AverageRating> averageRatingRaw();

    default Double averageRating() {
        List<AverageRating> values = averageRatingRaw();
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.get(0).getAvg();
    }

    interface CategoryCount {
        String getId();
        long getCount();
    }

    interface AverageRating {
        Double getAvg();
    }
}
