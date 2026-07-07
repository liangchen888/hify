package com.hify.knowledge.pgmapper;

import com.hify.knowledge.entity.Chunk;
import com.pgvector.PGvector;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * PostgreSQL / pgvector chunk 数据访问层。
 * 使用独立的 pgJdbcTemplate 操作 PostgreSQL，与 MyBatis-Plus 主数据源（MySQL）完全隔离。
 */
@Repository
public class ChunkRepository {

    private final JdbcTemplate pg;

    public ChunkRepository(@Qualifier("pgJdbcTemplate") JdbcTemplate pgJdbcTemplate) {
        this.pg = pgJdbcTemplate;
    }

    private static final RowMapper<Chunk> CHUNK_ROW_MAPPER = (rs, rowNum) -> {
        Chunk c = new Chunk();
        c.setId(rs.getLong("id"));
        c.setDocumentId(rs.getLong("document_id"));
        c.setChunkIndex(rs.getInt("chunk_index"));
        c.setContent(rs.getString("content"));
        c.setTokenCount(rs.getInt("token_count"));
        Timestamp createdAt = rs.getTimestamp("created_at");
        if (createdAt != null) c.setCreatedAt(createdAt.toLocalDateTime());
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        if (updatedAt != null) c.setUpdatedAt(updatedAt.toLocalDateTime());
        return c;
    };

    // ── 写操作 ─────────────────────────────────────────────────

    public void insert(Chunk chunk) {
        String sql = """
            INSERT INTO chunk
                (document_id, chunk_index, content, token_count, embedding, created_at, updated_at, deleted)
            VALUES (?, ?, ?, ?, ?::vector, ?, ?, 0)
            """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        LocalDateTime now = LocalDateTime.now();
        pg.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, chunk.getDocumentId());
            ps.setInt(2, chunk.getChunkIndex());
            ps.setString(3, chunk.getContent());
            ps.setInt(4, chunk.getTokenCount() != null ? chunk.getTokenCount() : 0);
            ps.setString(5, chunk.getEmbedding().toString());
            ps.setTimestamp(6, Timestamp.valueOf(now));
            ps.setTimestamp(7, Timestamp.valueOf(now));
            return ps;
        }, keyHolder);
        // PostgreSQL RETURNING returns a full row map; extract "id" explicitly
        if (keyHolder.getKeys() != null && keyHolder.getKeys().containsKey("id")) {
            chunk.setId(((Number) keyHolder.getKeys().get("id")).longValue());
        } else if (keyHolder.getKey() != null) {
            chunk.setId(keyHolder.getKey().longValue());
        }
    }

    public int deleteByDocumentId(Long documentId) {
        return pg.update("DELETE FROM chunk WHERE document_id = ?", documentId);
    }

    // ── 读操作 ─────────────────────────────────────────────────

    public List<Chunk> selectByDocumentId(Long documentId) {
        String sql = """
            SELECT id, document_id, chunk_index, content, token_count, created_at, updated_at
            FROM chunk
            WHERE document_id = ? AND deleted = 0
            ORDER BY chunk_index ASC
            """;
        return pg.query(sql, CHUNK_ROW_MAPPER, documentId);
    }

    /**
     * 向量相似度搜索。documentIds 由调用方从 MySQL 查出，避免跨库 JOIN。
     */
    public List<Chunk> searchByVector(List<Long> documentIds, PGvector embedding, int topK) {
        if (documentIds == null || documentIds.isEmpty()) return List.of();
        String placeholders = documentIds.stream().map(id -> "?").collect(Collectors.joining(","));
        String sql = """
            SELECT id, document_id, chunk_index, content, token_count, created_at, updated_at
            FROM chunk
            WHERE document_id IN (%s) AND deleted = 0
            ORDER BY embedding <-> ?::vector
            LIMIT ?
            """.formatted(placeholders);
        Object[] params = buildParams(documentIds, embedding.toString(), topK);
        return pg.query(sql, CHUNK_ROW_MAPPER, params);
    }

    /**
     * 向量相似度搜索，同时返回距离（用于相似度分数）。
     */
    public List<ChunkWithScore> searchByVectorWithScore(List<Long> documentIds, PGvector embedding, int topK) {
        if (documentIds == null || documentIds.isEmpty()) return List.of();
        String placeholders = documentIds.stream().map(id -> "?").collect(Collectors.joining(","));
        String sql = """
            SELECT id, document_id, chunk_index, content, token_count,
                   embedding <-> ?::vector AS distance,
                   created_at, updated_at
            FROM chunk
            WHERE document_id IN (%s) AND deleted = 0
            ORDER BY distance
            LIMIT ?
            """.formatted(placeholders);
        Object[] params = buildParamsWithEmbeddingFirst(embedding.toString(), documentIds, topK);
        return pg.query(sql, (rs, rowNum) -> {
            Chunk c = new Chunk();
            c.setId(rs.getLong("id"));
            c.setDocumentId(rs.getLong("document_id"));
            c.setChunkIndex(rs.getInt("chunk_index"));
            c.setContent(rs.getString("content"));
            c.setTokenCount(rs.getInt("token_count"));
            Timestamp createdAt = rs.getTimestamp("created_at");
            if (createdAt != null) c.setCreatedAt(createdAt.toLocalDateTime());
            return new ChunkWithScore(c, rs.getDouble("distance"));
        }, params);
    }

    // ── 工具方法 ───────────────────────────────────────────────

    /** documentIds..., embeddingStr, topK */
    private static Object[] buildParams(List<Long> documentIds, String embeddingStr, int topK) {
        Object[] params = new Object[documentIds.size() + 2];
        for (int i = 0; i < documentIds.size(); i++) params[i] = documentIds.get(i);
        params[documentIds.size()] = embeddingStr;
        params[documentIds.size() + 1] = topK;
        return params;
    }

    /** embeddingStr, documentIds..., topK */
    private static Object[] buildParamsWithEmbeddingFirst(String embeddingStr, List<Long> documentIds, int topK) {
        Object[] params = new Object[documentIds.size() + 2];
        params[0] = embeddingStr;
        for (int i = 0; i < documentIds.size(); i++) params[i + 1] = documentIds.get(i);
        params[documentIds.size() + 1] = topK;
        return params;
    }

    public record ChunkWithScore(Chunk chunk, double distance) {
        /** L2 距离转 0~1 相似度：score = 1 / (1 + distance) */
        public double score() {
            return 1.0 / (1.0 + distance);
        }
    }
}
