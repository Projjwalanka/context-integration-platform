package com.bank.aiassistant.model.dto.skg;

import java.util.Map;

public record SkgEdgeDto(
        String id,
        String sourceId,
        String targetId,
        String relType,    // CONTAINS, CALLS, DEPENDS_ON, EXPOSED_BY, USES_DB, HAS_FIELD, DESCRIBES, IMPLEMENTS, AFFECTS, MODIFIES
        Map<String, Object> properties
) {}
