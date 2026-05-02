package com.bank.aiassistant.repository;

import com.bank.aiassistant.model.entity.CdcSyncState;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CdcSyncStateRepository extends MongoRepository<CdcSyncState, String> {

    Optional<CdcSyncState> findByTenantIdAndConnectorId(String tenantId, String connectorId);

    List<CdcSyncState> findByTenantIdOrderByUpdatedAtDesc(String tenantId);
}
