package com.bank.aiassistant.service.ingestion;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Chunks documents using a <b>sliding-window token splitter</b>.
 *
 * <p>Enterprise-grade strategy:
 * <ul>
 *   <li>Chunk size: 512 tokens (fits comfortably in embedding model context)</li>
 *   <li>Overlap: 64 tokens (preserves cross-boundary semantics)</li>
 *   <li>Min chunk length: 50 chars (skips near-empty fragments)</li>
 *   <li>Metadata is propagated from parent to each child chunk</li>
 * </ul>
 */
@Slf4j
@Component
public class DocumentChunker {

    private static final int CHUNK_SIZE_TOKENS  = 512;
    private static final int OVERLAP_TOKENS     = 64;
    private static final int MIN_CHUNK_LENGTH   = 50;

    private final TokenTextSplitter splitter;

    public DocumentChunker() {
        this.splitter = new TokenTextSplitter(
                CHUNK_SIZE_TOKENS,
                OVERLAP_TOKENS,
                MIN_CHUNK_LENGTH,
                10_000,  // max documents per batch
                true     // keep separator
        );
    }

    /**
     * Splits a list of documents into chunks, inheriting all parent metadata.
     *
     * @param documents raw documents from readers
     * @param extraMeta additional metadata to attach to all chunks
     * @return flattened list of chunks ready for embedding
     */
    public List<Document> chunk(List<Document> documents, Map<String, Object> extraMeta) {
        List<Document> chunks = splitter.apply(documents);

        // Enrich each chunk with extra metadata
        if (extraMeta != null && !extraMeta.isEmpty()) {
            chunks = chunks.stream()
                    .map(chunk -> {
                        Map<String, Object> merged = new java.util.HashMap<>(chunk.getMetadata());
                        merged.putAll(extraMeta);
                        return new Document(chunk.getId(), chunk.getText(), merged);
                    })
                    .toList();
        }

        log.debug("Chunked {} documents → {} chunks", documents.size(), chunks.size());
        return chunks;
    }
}
