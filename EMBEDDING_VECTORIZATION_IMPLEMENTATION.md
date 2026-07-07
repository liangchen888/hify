# Hify 知识库向量化实现分析

## 问题：向量化是真实实现还是 Mock?

**用户问题**: "主流知识文档向量化是通过BGE或OpenAI embedding模型算出来的，hify是怎么做的向量化的，用什么模型或者工具，还是没有做"

**答案**: **当前 Hify 采用完整的 Mock 实现，没有使用任何真实的向量化模型（BGE、OpenAI Embedding等）。**

---

## 核心发现

### 1. Mock 存储架构

**文件**: `hify-knowledge/src/main/java/com/hify/knowledge/service/impl/KnowledgeServiceImpl.java`

```java
// 第 44-45 行 - Mock chunk 存储，替代真实的 pgvector
private static final ConcurrentHashMap<Long, List<ChunkVO>> MOCK_CHUNKS = new ConcurrentHashMap<>();
private static final ConcurrentHashMap<Long, String> DOC_RAW_TEXT = new ConcurrentHashMap<>();
```

**特点**:
- 用 `ConcurrentHashMap` 替代真实的向量数据库（如 PostgreSQL pgvector、Pinecone、Milvus等）
- 完全在内存中存储 chunk，程序重启数据丢失
- `DOC_RAW_TEXT` 暂存原始文件内容，处理后清除

---

## 向量化处理流程（Mock实现）

### 流程1：文档上传 → processDocument 异步处理

**关键位置**: 
- `uploadDocument()` 方法（第 111-161 行）
- `processDocument()` 方法（第 222-275 行）

#### 第一步：文件验证与内容读取 (uploadDocument)

```java
// 第 127-145 行 - 文件校验
String ext = originalName.contains(".")
    ? originalName.substring(originalName.lastIndexOf('.') + 1).toLowerCase()
    : "";
if (!ALLOWED_TYPES.contains(ext)) {  // 仅支持 txt, md, pdf
    throw new BizException(ErrorCode.PARAM_ERROR);
}
if (file.getSize() > MAX_SIZE) {  // 最大 10MB
    throw new BizException(ErrorCode.PARAM_ERROR);
}

// 第 148-155 行 - 内容读取（仅支持文本）
String rawText = "";
if ("txt".equals(ext) || "md".equals(ext)) {
    rawText = new String(file.getBytes(), StandardCharsets.UTF_8);
} else {
    rawText = "[PDF 文件：" + originalName + "，Mock 模式暂不解析 PDF 内容]";
}
```

**支持的文件类型**: `txt`, `md`, `pdf`（pdf只是占位，不真实解析）

#### 第二步：数据库记录与异步处理 (uploadDocument)

```java
// 第 160-171 行 - 创建 Document 记录
Document doc = new Document();
doc.setKnowledgeBaseId(kbId);
doc.setName(originalName);
doc.setFileType(ext);
doc.setFileSize(file.getSize());
doc.setStatus("PENDING");  // 初始状态
doc.setChunkCount(0);
documentMapper.insert(doc);

// 第 173-176 行 - 异步处理
if (!rawTextFinal.isBlank()) {
    DOC_RAW_TEXT.put(docId, rawTextFinal);
}
asyncExecutor.execute(() -> processDocument(docId));  // 后台异步执行
```

#### 第三步：文本分块处理 (processDocument)

```java
// 第 237-258 行 - 核心分块逻辑
String rawText = DOC_RAW_TEXT.remove(documentId);  // 获取原始内容
List<ChunkVO> chunks = new ArrayList<>();
if (rawText != null && !rawText.isBlank()) {
    // 按段落切割（双换行符 \n{2,} 作为分隔符）
    String[] paragraphs = rawText.split("\\n{2,}");
    for (int i = 0; i < paragraphs.length; i++) {
        String para = paragraphs[i].trim();
        if (para.isBlank()) continue;
        
        ChunkVO c = new ChunkVO();
        c.setId((long) (documentId * 1000 + i));
        c.setDocumentId(documentId);
        c.setChunkIndex(i);
        c.setContent(para);  // 段落内容
        c.setTokenCount(para.length() / 2 + 1);  // Mock token计数：字符数/2+1
        chunks.add(c);
    }
}
```

**Mock 实现特点**:
- ✅ **实现了**: 文本按段落（双换行符）分块
- ✅ **实现了**: ChunkVO 对象存储（id、content、chunkIndex等）
- ❌ **没实现**: 真实向量化计算
- ❌ **没实现**: Token 精确计数（只是简单公式 `length/2+1`）
- ❌ **没实现**: 向量嵌入存储

#### 第四步：状态更新

```java
// 第 260-266 行
doc.setChunkCount(chunkCount);
doc.setStatus("DONE");  // 标记完成
documentMapper.updateById(doc);
```

**处理流程耗时**: 模拟2-4秒（第 238 行）
```java
Thread.sleep(2000 + new Random().nextInt(2000));
```

---

## 向量化处理流程（Mock实现）

### 流程2：RAG 检索 → 返回 Mock 结果

**关键位置**: `searchChunks()` 方法（第 187-204 行）

```java
@Override
public List<ChunkVO> searchChunks(Long knowledgeBaseId, String query, int topK) {
    // 第 189-193 行 - 收集知识库下所有 DONE 文档的 chunk
    List<Document> docs = documentMapper.selectList(
        new LambdaQueryWrapper<Document>()
            .eq(Document::getKnowledgeBaseId, knowledgeBaseId)
            .eq(Document::getStatus, "DONE"));

    List<ChunkVO> all = new ArrayList<>();
    for (Document doc : docs) {
        List<ChunkVO> chunks = MOCK_CHUNKS.getOrDefault(doc.getId(), List.of());
        all.addAll(chunks);
    }

    // 第 201-204 行 - Mock 相似度排序
    // 真实实现应按向量余弦距离排序
    java.util.Collections.shuffle(all, new Random(query.hashCode()));
    List<ChunkVO> result = all.stream().limit(topK).toList();
    log.info("RAG mock 检索 kbId={} query='{}' 命中 {}/{} 条", 
             knowledgeBaseId, query, result.size(), all.size());
    return result;
}
```

**Mock 相似度搜索**:
- ❌ **没实现**: 基于向量的相似度计算
- ❌ **没实现**: 余弦距离排序
- ✅ **实现了**: 按 Query HashCode 随机打乱（伪相似度排序）
- ✅ **实现了**: 取前 topK 结果

**实际行为**: 
- 根据 query 的 hashcode 作为随机种子（`new Random(query.hashCode())`）
- 对所有 chunk 随机打乱
- 返回前 topK 条

这意味着相同的 query 会返回相同的结果顺序（确定性），但不是真实的相似度匹配。

---

## 向量化处理流程（Mock实现）

### 流程3：与 Chat 集成

**集成位置**: `hify-chat/src/main/java/com/hify/chat/service/impl/ChatServiceImpl.java`

```java
// 第 217 行 - 检查知识库
if (agent.getKnowledgeBaseId() != null) {
    chunks = knowledgeService.searchChunks(
        agent.getKnowledgeBaseId(), 
        userMessage, 
        10);  // 取前 10 条
}

// 第 243-250 行 - 将 chunks 添加到 System Prompt
String systemPromptFinal = buildSystemPrompt(
    agent.getSystemPrompt(), 
    chunks);
```

**System Prompt 的改造**:

```java
// 第 532-545 行 - buildSystemPrompt()
private String buildSystemPrompt(String originalPrompt, List<ChunkVO> chunks) {
    StringBuilder sb = new StringBuilder();
    sb.append(originalPrompt);
    
    if (!chunks.isEmpty()) {
        sb.append("\n\n【参考资料】\n");
        for (ChunkVO chunk : chunks) {
            sb.append(chunk.getContent()).append("\n");
        }
        sb.append("\n请基于以上参考资料回答用户问题。")
          .append("如果资料中没有相关信息，直接说「我没有找到相关资料」，")
          .append("不要编造。");
    }
    return sb.toString();
}
```

**集成特点**:
- ✅ **实现了**: RAG 检索与 Chat 的集成
- ✅ **实现了**: Chunk 内容拼接到 System Prompt
- ❌ **没实现**: 真实向量相似度排序（使用 Mock 随机排序）

---

## 数据结构

### ChunkVO（块对象）

```java
// hify-knowledge/src/main/java/com/hify/knowledge/dto/ChunkVO.java
public class ChunkVO {
    private Long id;              // 块 ID（Mock: documentId*1000+index）
    private Long documentId;      // 所属文档 ID
    private int chunkIndex;       // 块序号
    private String content;       // 文本内容（段落）
    private int tokenCount;       // Token 计数（Mock: length/2+1）
    private double[] embedding;   // 向量（当前为 null，未使用）
}
```

### Document（文档对象）

```java
public class Document {
    private Long id;
    private Long knowledgeBaseId;
    private String name;
    private String fileType;      // txt, md, pdf
    private long fileSize;
    private String status;        // PENDING → PROCESSING → DONE/FAILED
    private String errorMessage;
    private int chunkCount;       // 实际生成的 chunk 数量
}
```

---

## 完整处理流程

```
用户上传文件
    ↓
uploadDocument()
    ├─ 校验文件类型/大小
    ├─ 读取文件内容（txt/md）或占位符（pdf）
    ├─ 创建 Document 记录，status="PENDING"
    └─ 异步 processDocument()
        ↓
    processDocument()（后台异步）
        ├─ status = "PROCESSING"
        ├─ 模拟耗时 2-4 秒
        ├─ 按 \n{2,} 分割文本为段落
        ├─ 为每个段落创建 ChunkVO
        ├─ 存入 MOCK_CHUNKS（ConcurrentHashMap）
        ├─ 清除 DOC_RAW_TEXT 中的原始内容
        ├─ status = "DONE"，chunkCount = N
        └─ 返回
        ↓
用户提问 → Chat 服务
    ├─ 检查 Agent.knowledgeBaseId
    ├─ 调用 searchChunks(knowledgeBaseId, query, 10)
    │   ├─ 收集所有 DONE 状态文档的 chunks
    │   ├─ 用 query.hashCode() 作随机种子
    │   ├─ 随机打乱 chunks（伪相似度排序）
    │   └─ 返回前 10 条
    ├─ buildSystemPrompt()
    │   ├─ 原始 System Prompt
    │   ├─ + 【参考资料】
    │   ├─ + 所有 chunk.content
    │   └─ + 说明指令
    ├─ 将 messages 发送给 LLM
    └─ LLM 返回回答
```

---

## Mock vs 真实实现对比

| 功能模块 | Mock 实现 | 真实实现应该是 |
|---------|---------|-------------|
| **文档上传** | ✅ 支持 txt/md/pdf | ✅ 相同 |
| **文本分块** | ✅ 按段落分割 | ✅ 相同（或更复杂） |
| **向量生成** | ❌ 无 | ✅ BGE/OpenAI/本地模型生成 embedding |
| **Token 计数** | Mock: `length/2+1` | ✅ 精确计数（调用分词器） |
| **存储** | ConcurrentHashMap（内存） | ✅ pgvector/Pinecone/Milvus |
| **相似度搜索** | Mock: 随机打乱 | ✅ 向量余弦距离排序 |
| **性能** | 快（内存存储） | 较慢（向量查询） |
| **精度** | 低（随机排序） | 高（语义相似度） |

---

## 为什么是 Mock?

从代码注释和实现可以看出，这是**架构设计的第一阶段**:

1. **注释明确标注**: "Mock chunk 存储（替代 pgvector）"、"真实实现应调用 Embedding API + pgvector"
2. **意图明确**: 第一版本专注于 **功能完整性**，不追求向量化的精度
3. **易于扩展**: 
   - 替换 `searchChunks()` 中的随机排序为真实的向量相似度计算
   - 从 `ConcurrentHashMap` 迁移到 pgvector
   - 添加真实的 Embedding 模型调用

---

## 集成点总结

### 向量化在 Hify 中的位置

```
Hify Architecture
│
├─ hify-agent
│  └─ Agent.systemPrompt, Agent.knowledgeBaseId
│
├─ hify-knowledge ← 向量化实现所在
│  ├─ KnowledgeServiceImpl
│  │  ├─ processDocument()  ← 文本分块处理
│  │  └─ searchChunks()     ← RAG 检索（Mock）
│  └─ ChunkVO               ← 块数据结构
│
└─ hify-chat
   └─ ChatServiceImpl
      ├─ searchChunks() 调用处（第 217 行）
      └─ buildSystemPrompt() 将 chunks 加入（第 532 行）
```

---

## 关键代码位置快速索引

| 功能 | 文件 | 行号 |
|-----|-----|-----|
| Mock 存储初始化 | KnowledgeServiceImpl | 44-45 |
| 文件上传入口 | KnowledgeServiceImpl | 111-176 |
| 文本分块处理 | KnowledgeServiceImpl | 237-258 |
| Mock 相似度搜索 | KnowledgeServiceImpl | 201-204 |
| Chat 集成 - 检索调用 | ChatServiceImpl | 217 |
| Chat 集成 - System Prompt | ChatServiceImpl | 532-545 |

---

## 总结答案

### 问题：Hify 用了什么向量化模型或工具？

**答案**: 
- ❌ **没有使用 BGE、OpenAI Embedding 等真实模型**
- ❌ **没有使用 pgvector、Pinecone 等向量数据库**
- ✅ **使用 Mock 实现**：
  - 文本按段落分块 ✓
  - 内存存储（ConcurrentHashMap）✓
  - 随机排序模拟相似度搜索（伪实现）✓

### 工作原理

1. **上传文档** → 异步分块（按 `\n{2,}` 切割）
2. **存储 chunks** → 内存中的 ConcurrentHashMap
3. **用户查询** → Mock 相似度搜索（实际上是按 query hashcode 的伪随机排序）
4. **添加到 prompt** → 将前 10 条 chunks 拼接到 System Prompt

### 与真实实现的差异

- **精度**: Mock 返回随机顺序，真实应该按向量余弦距离排序
- **可靠性**: Mock 程序重启丢失，真实应该持久化到数据库
- **可扩展性**: Mock 适合演示/开发，真实需要向量化引擎

---

## 扩展建议

如果要从 Mock 升级到真实向量化，需要：

```java
// TODO: 替换 searchChunks() 的相似度排序
// 从:  Collections.shuffle(all, new Random(query.hashCode()));
// 改为: QueryVector qVec = embeddingService.embed(query);
//     return pgVector.search(qVec, topK);

// TODO: 添加 EmbeddingService
// - 支持 OpenAI API / BGE 本地模型 / Ollama
// - 返回向量数组 float[]

// TODO: 迁移存储到 pgvector
// - 为 chunk 添加 embedding 字段
// - 创建向量索引 USING IVFFLAT
```
