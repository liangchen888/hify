package com.hify.knowledge.dto;

import com.hify.knowledge.entity.Chunk;
import lombok.Getter;
import lombok.Setter;

/**
 * Chunk 视图对象，用于 API 响应。
 * 不包含 embedding 字段（向量不返回给前端）。
 */
@Getter @Setter
public class ChunkVO {
    private Long id;
    private Long documentId;
    private Integer chunkIndex;
    private String content;
    private Integer tokenCount;

    /**
     * 将 Chunk 实体转换为 VO，安全排除 embedding 字段。
     */
    public static ChunkVO from(Chunk chunk) {
        ChunkVO vo = new ChunkVO();
        vo.setId(chunk.getId());
        vo.setDocumentId(chunk.getDocumentId());
        vo.setChunkIndex(chunk.getChunkIndex());
        vo.setContent(chunk.getContent());
        vo.setTokenCount(chunk.getTokenCount());
        return vo;
    }
}
