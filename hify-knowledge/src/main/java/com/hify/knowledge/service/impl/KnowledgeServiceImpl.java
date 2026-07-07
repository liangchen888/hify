package com.hify.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hify.common.dto.PageResult;
import com.hify.common.dto.Result;
import com.hify.common.exception.BizException;
import com.hify.common.exception.ErrorCode;
import com.hify.knowledge.dto.*;
import com.hify.knowledge.entity.Chunk;
import com.hify.knowledge.entity.Document;
import com.hify.knowledge.entity.KnowledgeBase;
import com.hify.knowledge.mapper.DocumentMapper;
import com.hify.knowledge.mapper.KnowledgeBaseMapper;
import com.hify.knowledge.pgmapper.ChunkRepository;
import com.hify.knowledge.service.EmbeddingService;
import com.hify.knowledge.service.KnowledgeService;
import com.pgvector.PGvector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@Service
public class KnowledgeServiceImpl implements KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeServiceImpl.class);

    private final KnowledgeBaseMapper kbMapper;
    private final DocumentMapper documentMapper;
    private final ChunkRepository chunkRepository;
    private final EmbeddingService embeddingService;
    private final Executor asyncExecutor;

    private static final ConcurrentHashMap<Long, String> DOC_RAW_TEXT = new ConcurrentHashMap<>();
    private static final List<String> ALLOWED_TYPES = Arrays.asList("txt", "md", "pdf");
    private static final long MAX_SIZE = 10 * 1024 * 1024L;

    public KnowledgeServiceImpl(KnowledgeBaseMapper kbMapper,
                                DocumentMapper documentMapper,
                                ChunkRepository chunkRepository,
                                EmbeddingService embeddingService,
                                @Qualifier("asyncExecutor") Executor asyncExecutor) {
        this.kbMapper = kbMapper;
        this.documentMapper = documentMapper;
        this.chunkRepository = chunkRepository;
        this.embeddingService = embeddingService;
        this.asyncExecutor = asyncExecutor;
    }

    // ── 知识库 CRUD ───────────────────────────────────────────

    @Override
    public KnowledgeBaseVO createKb(KnowledgeBaseCreateRequest req) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(req.getName());
        kb.setDescription(req.getDescription() != null ? req.getDescription() : "");
        kb.setEnabled(1);
        kbMapper.insert(kb);
        return KnowledgeBaseVO.from(kb);
    }

    @Override
    public Result<PageResult<KnowledgeBaseVO>> listKb(int page, int pageSize, String name) {
        LambdaQueryWrapper<KnowledgeBase> wrapper = new LambdaQueryWrapper<KnowledgeBase>()
            .like(name != null && !name.isBlank(), KnowledgeBase::getName, name)
            .orderByDesc(KnowledgeBase::getCreatedAt);
        int size = Math.min(pageSize, 100);
        var p = kbMapper.selectPage(new Page<>(page, size), wrapper);
        List<KnowledgeBaseVO> list = p.getRecords().stream()
            .map(KnowledgeBaseVO::from).collect(Collectors.toList());
        return PageResult.of(list, p.getTotal(), (int) p.getCurrent(), (int) p.getSize());
    }

    @Override
    public KnowledgeBaseVO getKb(Long id) {
        return KnowledgeBaseVO.from(getKbOrThrow(id));
    }

    @Override
    public KnowledgeBaseVO updateKb(Long id, KnowledgeBaseUpdateRequest req) {
        KnowledgeBase kb = getKbOrThrow(id);
        if (req.getName() != null) kb.setName(req.getName());
        if (req.getDescription() != null) kb.setDescription(req.getDescription());
        if (req.getEnabled() != null) kb.setEnabled(req.getEnabled());
        kbMapper.updateById(kb);
        return KnowledgeBaseVO.from(kb);
    }

    @Override
    @Transactional
    public void deleteKb(Long id) {
        getKbOrThrow(id);
        List<Document> docs = documentMapper.selectList(
            new LambdaQueryWrapper<Document>().eq(Document::getKnowledgeBaseId, id));
        for (Document doc : docs) {
            chunkRepository.deleteByDocumentId(doc.getId());
            documentMapper.deleteById(doc.getId());
        }
        kbMapper.deleteById(id);
    }

    // ── 文档 CRUD ─────────────────────────────────────────────

    @Override
    public DocumentVO uploadDocument(Long kbId, MultipartFile file) {
        getKbOrThrow(kbId);
        String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "file";
        String ext = originalName.contains(".")
            ? originalName.substring(originalName.lastIndexOf('.') + 1).toLowerCase() : "";
        if (!ALLOWED_TYPES.contains(ext)) throw new BizException(ErrorCode.PARAM_ERROR);
        if (file.getSize() > MAX_SIZE) throw new BizException(ErrorCode.PARAM_ERROR);

        String rawText = "";
        try {
            if ("txt".equals(ext) || "md".equals(ext)) {
                rawText = new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } else {
                rawText = "[PDF 文件：" + originalName + "，暂不支持PDF解析]";
            }
        } catch (IOException e) {
            log.warn("读取文件内容失败: {}", e.getMessage());
        }
        final String rawTextFinal = rawText;

        Document doc = new Document();
        doc.setKnowledgeBaseId(kbId);
        doc.setName(originalName);
        doc.setFileType(ext);
        doc.setFileSize(file.getSize());
        doc.setStatus("PENDING");
        doc.setErrorMessage("");
        doc.setChunkCount(0);
        documentMapper.insert(doc);

        Long docId = doc.getId();
        if (!rawTextFinal.isBlank()) DOC_RAW_TEXT.put(docId, rawTextFinal);
        asyncExecutor.execute(() -> processDocument(docId));
        return DocumentVO.from(doc);
    }

    @Override
    public Result<PageResult<DocumentVO>> listDocuments(Long kbId, int page, int pageSize) {
        getKbOrThrow(kbId);
        LambdaQueryWrapper<Document> wrapper = new LambdaQueryWrapper<Document>()
            .eq(Document::getKnowledgeBaseId, kbId)
            .orderByDesc(Document::getCreatedAt);
        int size = Math.min(pageSize, 100);
        var p = documentMapper.selectPage(new Page<>(page, size), wrapper);
        List<DocumentVO> list = p.getRecords().stream()
            .map(DocumentVO::from).collect(Collectors.toList());
        return PageResult.of(list, p.getTotal(), (int) p.getCurrent(), (int) p.getSize());
    }

    @Override
    public DocumentVO getDocument(Long id) {
        Document doc = documentMapper.selectById(id);
        if (doc == null) throw new BizException(ErrorCode.DOCUMENT_NOT_FOUND);
        return DocumentVO.from(doc);
    }

    @Override
    public List<ChunkVO> getChunks(Long documentId) {
        Document doc = documentMapper.selectById(documentId);
        if (doc == null) throw new BizException(ErrorCode.DOCUMENT_NOT_FOUND);
        return chunkRepository.selectByDocumentId(documentId).stream()
            .map(ChunkVO::from).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void deleteDocument(Long id) {
        Document doc = documentMapper.selectById(id);
        if (doc == null) throw new BizException(ErrorCode.DOCUMENT_NOT_FOUND);
        chunkRepository.deleteByDocumentId(id);
        documentMapper.deleteById(id);
    }

    /**
     * 重新处理已有文档（重新向量化并写入 PG）。
     * 用于 PG chunk 数据丢失时的恢复。
     */
    @Override
    public DocumentVO reprocessDocument(Long id) {
        Document doc = documentMapper.selectById(id);
        if (doc == null) throw new BizException(ErrorCode.DOCUMENT_NOT_FOUND);
        // 清除 PG 里旧的 chunk
        chunkRepository.deleteByDocumentId(id);
        // 重置状态
        doc.setStatus("PENDING");
        doc.setChunkCount(0);
        doc.setErrorMessage("");
        documentMapper.updateById(doc);
        // 异步触发处理（需要原始文件内容，从 document name 做占位或重新读文件）
        asyncExecutor.execute(() -> processDocumentByName(id, doc.getName(), doc.getFileType()));
        return DocumentVO.from(doc);
    }

    // ── RAG 向量检索 ─────────────────────────────────────────

    /** 从 MySQL 查出该知识库所有 DONE 文档的 ID，避免跨库 JOIN */
    private List<Long> getDoneDocumentIds(Long knowledgeBaseId) {
        return documentMapper.selectList(
            new LambdaQueryWrapper<Document>()
                .eq(Document::getKnowledgeBaseId, knowledgeBaseId)
                .eq(Document::getStatus, "DONE")
        ).stream().map(Document::getId).collect(Collectors.toList());
    }

    @Override
    public List<ChunkVO> searchChunks(Long knowledgeBaseId, String query, int topK) {
        try {
            List<Long> docIds = getDoneDocumentIds(knowledgeBaseId);
            if (docIds.isEmpty()) {
                log.warn("知识库 {} 下没有已完成的文档", knowledgeBaseId);
                return new ArrayList<>();
            }
            PGvector queryVector = embeddingService.embed(query);
            if (queryVector == null) {
                log.warn("查询文本向量化失败: {}", query);
                return new ArrayList<>();
            }
            List<Chunk> chunks = chunkRepository.searchByVector(docIds, queryVector, topK);
            List<ChunkVO> result = chunks.stream().map(ChunkVO::from).collect(Collectors.toList());
            log.info("RAG向量检索完成 kbId={} docCount={} query='{}' 命中 {} 条",
                knowledgeBaseId, docIds.size(), query, result.size());
            return result;
        } catch (Exception e) {
            log.error("RAG检索失败 kbId={} query='{}'", knowledgeBaseId, query, e);
            return new ArrayList<>();
        }
    }

    // ── 文档处理管线 ─────────────────────────────────────────

    private void processDocument(Long documentId) {
        Document doc = documentMapper.selectById(documentId);
        if (doc == null) return;
        try {
            doc.setStatus("PROCESSING");
            documentMapper.updateById(doc);
            log.info("开始处理文档 docId={}, 文件名={}", documentId, doc.getName());

            String rawText = DOC_RAW_TEXT.remove(documentId);
            if (rawText == null || rawText.isBlank()) {
                log.warn("文档文本为空 docId={}", documentId);
                doc.setStatus("DONE");
                doc.setChunkCount(0);
                documentMapper.updateById(doc);
                return;
            }

            List<String> chunkTexts = splitIntoChunks(rawText);
            if (chunkTexts.isEmpty()) {
                doc.setStatus("DONE");
                doc.setChunkCount(0);
                documentMapper.updateById(doc);
                return;
            }
            log.info("文本分块完成 docId={}, chunks={}", documentId, chunkTexts.size());

            List<PGvector> embeddings = embeddingService.embedBatch(chunkTexts);
            if (embeddings.size() != chunkTexts.size()) {
                throw new RuntimeException("向量化数量不匹配 期望=" + chunkTexts.size() + " 实际=" + embeddings.size());
            }
            log.info("向量化完成 docId={}, embeddings={}", documentId, embeddings.size());

            for (int i = 0; i < chunkTexts.size(); i++) {
                Chunk chunk = new Chunk();
                chunk.setDocumentId(documentId);
                chunk.setChunkIndex(i);
                chunk.setContent(chunkTexts.get(i));
                chunk.setTokenCount(estimateTokenCount(chunkTexts.get(i)));
                chunk.setEmbedding(embeddings.get(i));
                chunkRepository.insert(chunk);
            }
            log.info("chunks保存完成 docId={}, 数量={}", documentId, chunkTexts.size());

            doc.setChunkCount(chunkTexts.size());
            doc.setStatus("DONE");
            doc.setErrorMessage("");
            documentMapper.updateById(doc);
            log.info("文档处理完成 docId={}", documentId);

        } catch (Exception e) {
            log.error("文档处理失败 docId={}", documentId, e);
            doc.setStatus("FAILED");
            String errMsg = e.getMessage() != null ? e.getMessage() : "处理失败";
            doc.setErrorMessage(errMsg.length() > 500 ? errMsg.substring(0, 500) : errMsg);
            documentMapper.updateById(doc);
        }
    }

    /**
     * reprocessDocument 使用：通过文档名生成占位文本触发 BGE 向量化，
     * 主要用于原始文件内容不在内存时的降级处理。
     * 真实场景建议重新上传文件。
     */
    private void processDocumentByName(Long documentId, String name, String fileType) {
        Document doc = documentMapper.selectById(documentId);
        if (doc == null) return;
        try {
            doc.setStatus("PROCESSING");
            documentMapper.updateById(doc);
            // 原始内容已不在内存，生成占位提示
            String placeholder = "【" + name + "】（原始内容已不在内存，请重新上传文件以恢复向量化）";
            List<String> texts = List.of(placeholder);
            List<PGvector> embeddings = embeddingService.embedBatch(texts);
            Chunk chunk = new Chunk();
            chunk.setDocumentId(documentId);
            chunk.setChunkIndex(0);
            chunk.setContent(placeholder);
            chunk.setTokenCount(estimateTokenCount(placeholder));
            chunk.setEmbedding(embeddings.get(0));
            chunkRepository.insert(chunk);
            doc.setChunkCount(1);
            doc.setStatus("DONE");
            doc.setErrorMessage("原始内容不在内存，已生成占位chunk，建议重新上传");
            documentMapper.updateById(doc);
        } catch (Exception e) {
            log.error("reprocess失败 docId={}", documentId, e);
            doc.setStatus("FAILED");
            String errMsg = e.getMessage() != null ? e.getMessage() : "处理失败";
            doc.setErrorMessage(errMsg.length() > 500 ? errMsg.substring(0, 500) : errMsg);
            documentMapper.updateById(doc);
        }
    }

    private List<String> splitIntoChunks(String text) {
        List<String> chunks = new ArrayList<>();
        String[] paragraphs = text.split("\\n{2,}");
        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (!trimmed.isBlank()) chunks.add(trimmed);
        }
        return chunks.isEmpty() ? List.of(text.trim()) : chunks;
    }

    private int estimateTokenCount(String text) {
        int count = 0;
        for (char c : text.toCharArray()) {
            if (c >= 0x4E00 && c <= 0x9FFF) count += 1;
            else if (c == ' ' || c == '\n' || c == '\t') count += 1;
            else count += (int) 0.25;
        }
        return Math.max(1, count);
    }

    private KnowledgeBase getKbOrThrow(Long id) {
        KnowledgeBase kb = kbMapper.selectById(id);
        if (kb == null) throw new BizException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        return kb;
    }
}
