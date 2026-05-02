package com.bank.aiassistant.repository;

import com.bank.aiassistant.model.entity.KgRelationship;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KgRelationshipRepository extends MongoRepository<KgRelationship, String> {

    List<KgRelationship> findByTenantIdAndSourceEntityIdAndDeprecatedFalse(String tenantId, String sourceEntityId);

    List<KgRelationship> findByTenantIdAndTargetEntityIdAndDeprecatedFalse(String tenantId, String targetEntityId);

    /** Batch load outgoing edges for a set of source nodes (BFS hop). */
    List<KgRelationship> findByTenantIdAndSourceEntityIdInAndDeprecatedFalse(String tenantId, List<String> sourceEntityIds);

    /** Batch load incoming edges for a set of target nodes. */
    List<KgRelationship> findByTenantIdAndTargetEntityIdInAndDeprecatedFalse(String tenantId, List<String> targetEntityIds);

    long countByTenantIdAndDeprecatedFalse(String tenantId);

    void deleteByTenantIdAndSourceEntityId(String tenantId, String sourceEntityId);

    void deleteByTenantIdAndTargetEntityId(String tenantId, String targetEntityId);
}
