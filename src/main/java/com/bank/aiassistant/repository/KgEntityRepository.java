package com.bank.aiassistant.repository;

import com.bank.aiassistant.model.entity.KgEntity;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KgEntityRepository extends MongoRepository<KgEntity, String> {

    List<KgEntity> findByTenantIdAndDeprecatedFalseOrderByUpdatedAtDesc(String tenantId);

    List<KgEntity> findByTenantIdAndEntityTypeAndDeprecatedFalse(String tenantId, String entityType);

    List<KgEntity> findByTenantIdAndSourceConnectorIdAndDeprecatedFalse(String tenantId, String sourceConnectorId);

    List<KgEntity> findByTenantIdAndSourceTypeAndDeprecatedFalse(String tenantId, String sourceType);

    Optional<KgEntity> findByTenantIdAndSourceRefAndSourceType(String tenantId, String sourceRef, String sourceType);

    List<KgEntity> findByTenantIdAndNameContainingIgnoreCaseAndDeprecatedFalse(String tenantId, String namePart);

    List<KgEntity> findByIdIn(List<String> ids);

    long countByTenantIdAndDeprecatedFalse(String tenantId);

    long countByTenantIdAndEntityTypeAndDeprecatedFalse(String tenantId, String entityType);

    void deleteByTenantId(String tenantId);
}
