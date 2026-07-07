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
        String embStr = rs.getString("embedding");
        if (embStr != null) {
            c.setEmbedding(parseVector(embStr));
        }
        Timestamp createdAt = rs.getTimestamp("created_at");
        if (createdAt != null) c.setCreatedAt(createdAt.toLocalDateTime());
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        if (updatedAt != null) c.setUpdatedAt(updatedAt.toLocalDateTime());
        return c;
    };

    // ── 写操作 ─────────────────────────────────────────────────

    /**
     * 插入一条 chunk，embedding 使用 pgvector::vector 类型转换。
     */
    public void insert(Chunk chunk) {
        String sql = """
            INSERT INTO chunk
                (document_id, chunk_index, content, token_count, embedding, created_at, updated_at, deleted)
            VALUES
                (?, ?, ?, ?, ?::vector, ?, ?, 0)
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

        // PostgreSQL RETURNING returns a full row map; extract the "id" column explicitly
        if (keyHolder.getKeys() != null && keyHolder.getKeys().containsKey("id")) {
            Object idVal = keyHolder.getKeys().get("id");
            chunk.setId(((Number) idVal).longValue());
        } else if (keyHolder.getKey() != null) {
            chunk.setId(keyHolder.getKey().longValue());
        }
    }

    /**
     * 物理删除文档下所有 chunks。
     */
    public int deleteByDocumentId(Long documentId) {
        return pg.update("DELETE FROM chunk WHERE document_id = ?", documentId);
    }

    // ── 读操作 ─────────────────────────────────────────────────

    /**
     * 查询指定文档下的所有 chunks，按 chunk_index 升序。
     */
    public List<Chunk> selectByDocumentId(Long documentId) {
        String sql = """
            SELECT id, document_id, chunk_index, content, token_count, embedding,
                   created_at, updated_at
            FROM chunk
            WHERE document_id = ? AND deleted = 0
            ORDER BY chunk_index ASC
            """;
        return pg.query(sql, CHUNK_ROW_MAPPER, documentId);
    }

    /**
     * 向量相似度搜索，使用 pgvector <-> L2 距离算子。
     * documentIds 由调用方从 MySQL 查出，避免跨库 JOIN。
     */
    public List<Chunk> searchByVector(List<Long> documentIds, PGvector embedding, int topK) {
        if (documentIds == null || documentIds.isEmpty()) return List.of();
        String placeholders = documentIds.stream().map(id -> "?").collect(java.util.stream.Collectors.joining(","));
        String sql = """
            SELECT id, document_id, chunk_index, content, token_count, embedding,
                   created_at, updated_at
            FROM chunk
            WHERE document_id IN (%s)
              AND deleted = 0
            ORDER BY embedding <-> ?::vector
            LIMIT ?
            """.formatted(placeholders);
        // 参数：documentIds... + embedding + topK
        Object[] params = buildParams(documentIds, embedding.toString(), topK);
        return pg.query(sql, CHUNK_ROW_MAPPER, params);
    }

    /**
     * 向量相似度搜索，同时返回 L2 距离值（用于计算相似度分数）。
     * documentIds 由调用方从 MySQL 查出，避免跨库 JOIN。
     */
    public List<ChunkWithScore> searchByVectorWithScore(List<Long> documentIds, PGvector embedding, int topK) {
        if (documentIds == null || documentIds.isEmpty()) return List.of();
        String placeholders = documentIds.stream().map(id -> "?").collect(java.util.stream.Collectors.joining(","));
        String sql = """
            SELECT id, document_id, chunk_index, content, token_count,
                   embedding <-> ?::vector AS distance,
                   created_at, updated_at
            FROM chunk
            WHERE document_id IN (%s)
              AND deleted = 0
            ORDER BY distance
            LIMIT ?
            """.formatted(placeholders);
        // 参数：embedding + documentIds... + topK
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
            double distance = rs.getDouble("distance");
            return new ChunkWithScore(c, distance);
        }, params);
    }

    // ── 内部工具 ───────────────────────────────────────────────

    /** 构建参数数组：documentIds... , embeddingStr, topK */
    private static Object[] buildParams(List<Long> documentIds, String embeddingStr, int topK) {
        Object[] params = new Object[documentIds.size() + 2];
        for (int i = 0; i < documentIds.size(); i++) params[i] = documentIds.get(i);
        params[documentIds.size()] = embeddingStr;
        params[documentIds.size() + 1] = topK;
        return params;
    }

    /** 构建参数数组：embeddingStr, documentIds..., topK */
    private static Object[] buildParamsWithEmbeddingFirst(String embeddingStr, List<Long> documentIds, int topK) {
        Object[] params = new Object[documentIds.size() + 2];
        params[0] = embeddingStr;
        for (int i = 0; i < documentIds.size(); i++) params[i + 1] = documentIds.get(i);
        params[documentIds.size() + 1] = topK;
        return params;
    }

    private static PGvector parseVector(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("[")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        String[] parts = trimmed.split(",");
        float[] floats = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            floats[i] = Float.parseFloat(parts[i].trim());
        }
        return new PGvector(floats);
    }

    /**
     * Chunk + L2 距离，用于相似度计算。
     */
    public record ChunkWithScore(Chunk chunk, double distance) {
        /**
         * 将 L2 距离转为 0~1 相似度分数：score = 1 / (1 + distance)
         * 距离 0 → score 1.0（完全相同），距离越大 score 越小。
         */
        public double score() {
            return 1.0 / (1.0 + distance);
        }
    }
}
