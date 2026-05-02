package com.bank.aiassistant.controller;

import com.bank.aiassistant.context.TenantContext;
import com.bank.aiassistant.model.entity.CdcSyncState;
import com.bank.aiassistant.model.entity.KgEntity;
import com.bank.aiassistant.model.entity.KgRelationship;
import com.bank.aiassistant.repository.KgEntityRepository;
import com.bank.aiassistant.repository.KgRelationshipRepository;
import com.bank.aiassistant.service.kg.CdcSyncService;
import com.bank.aiassistant.service.kg.KnowledgeGraphService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for Knowledge Graph operations.
 *
 * All operations are tenant-scoped — the tenant is derived from the
 * authenticated user's email domain automatically.
 */
@RestController
@RequestMapping("/api/kg")
@RequiredArgsConstructor
public class KnowledgeGraphController {

    private final KnowledgeGraphService kgService;
    private final CdcSyncService        cdcSyncService;
    private final KgEntityRepository    entityRepo;
    private final KgRelationshipRepository relRepo;

    // ─────────────────────────────────────────────────────────────────────────
    // Stats
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/stats")
    public ResponseEntity<KnowledgeGraphService.KgStats> stats(
            @AuthenticationPrincipal UserDetails principal) {
        String tenantId = TenantContext.fromEmail(principal.getUsername());
        return ResponseEntity.ok(kgService.stats(tenantId));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Entities
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/entities")
    public ResponseEntity<List<KgEntity>> listEntities(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String connectorId,
            @AuthenticationPrincipal UserDetails principal) {

        String tenantId = TenantContext.fromEmail(principal.getUsername());

        List<KgEntity> results;
        if (search != null && !search.isBlank()) {
            results = entityRepo.findByTenantIdAndNameContainingIgnoreCaseAndDeprecatedFalse(tenantId, search);
        } else if (type != null) {
            results = entityRepo.findByTenantIdAndEntityTypeAndDeprecatedFalse(tenantId, type.toUpperCase());
        } else if (connectorId != null) {
            results = entityRepo.findByTenantIdAndSourceConnectorIdAndDeprecatedFalse(tenantId, connectorId);
        } else {
            results = entityRepo.findByTenantIdAndDeprecatedFalseOrderByUpdatedAtDesc(tenantId);
        }

        return ResponseEntity.ok(results);
    }

    @GetMapping("/entities/{id}")
    public ResponseEntity<KgEntity> getEntity(
            @PathVariable String id,
            @AuthenticationPrincipal UserDetails principal) {
        String tenantId = TenantContext.fromEmail(principal.getUsername());
        return entityRepo.findById(id)
                .filter(e -> tenantId.equals(e.getTenantId()))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/entities/{id}/neighbors")
    public ResponseEntity<KnowledgeGraphService.GraphSubgraph> getNeighbors(
            @PathVariable String id,
            @RequestParam(defaultValue = "1") int hops,
            @AuthenticationPrincipal UserDetails principal) {
        String tenantId = TenantContext.fromEmail(principal.getUsername());
        KnowledgeGraphService.GraphSubgraph subgraph = kgService.traverse(tenantId, List.of(id), Math.min(hops, 2));
        return ResponseEntity.ok(subgraph);
    }

    @DeleteMapping("/entities/{id}")
    public ResponseEntity<Void> deleteEntity(
            @PathVariable String id,
            @AuthenticationPrincipal UserDetails principal) {
        String tenantId = TenantContext.fromEmail(principal.getUsername());
        entityRepo.findById(id)
                .filter(e -> tenantId.equals(e.getTenantId()))
                .ifPresent(e -> kgService.deleteEntity(tenantId, id));
        return ResponseEntity.noContent().build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Relationships
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/relationships")
    public ResponseEntity<List<KgRelationship>> getRelationships(
            @RequestParam(required = false) String sourceEntityId,
            @RequestParam(required = false) String targetEntityId,
            @AuthenticationPrincipal UserDetails principal) {

        String tenantId = TenantContext.fromEmail(principal.getUsername());

        if (sourceEntityId != null) {
            return ResponseEntity.ok(
                    relRepo.findByTenantIdAndSourceEntityIdAndDeprecatedFalse(tenantId, sourceEntityId));
        }
        if (targetEntityId != null) {
            return ResponseEntity.ok(
                    relRepo.findByTenantIdAndTargetEntityIdAndDeprecatedFalse(tenantId, targetEntityId));
        }
        return ResponseEntity.badRequest().build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Graph search
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/search")
    public ResponseEntity<KnowledgeGraphService.GraphSubgraph> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "5") int maxSeeds,
            @AuthenticationPrincipal UserDetails principal) {
        String tenantId = TenantContext.fromEmail(principal.getUsername());
        return ResponseEntity.ok(kgService.searchAndTraverse(tenantId, q, maxSeeds));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CDC sync states
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/cdc/states")
    public ResponseEntity<List<CdcSyncState>> cdcStates(
            @AuthenticationPrincipal UserDetails principal) {
        String tenantId = TenantContext.fromEmail(principal.getUsername());
        return ResponseEntity.ok(cdcSyncService.listForTenant(tenantId));
    }

    @PostMapping("/cdc/reset/{connectorId}")
    public ResponseEntity<Map<String, String>> resetCdc(
            @PathVariable String connectorId,
            @AuthenticationPrincipal UserDetails principal) {
        String tenantId = TenantContext.fromEmail(principal.getUsername());
        // Re-create CDC state (forces full re-sync on next ingestion)
        cdcSyncService.getOrCreate(tenantId, connectorId, "UNKNOWN");
        return ResponseEntity.ok(Map.of("message", "CDC state reset — next sync will be a full re-index"));
    }
}
