# Hify 文档向量化处理完整指南

## 概述

Hify 的知识库向量化处理包括**三个主要流程**：
1. **知识库管理**：创建、更新、删除知识库
2. **文档上传和处理**：上传文档→自动分块（chunking）→Mock 存储
3. **RAG 检索**：基于用户查询检索最相关的 chunks

---

## 核心代码位置

| 功能 | 文件路径 | 关键方法 |
|------|--------|--------|
| **Service 接口** | `hify-knowledge/src/main/java/com/hify/knowledge/service/KnowledgeService.java` | - |
| **Service 实现** | `hify-knowledge/src/main/java/com/hify/knowledge/service/impl/KnowledgeServiceImpl.java` | `uploadDocument()`, `processDocument()`, `searchChunks()` |
| **Entity** | `hify-knowledge/src/main/java/com/hify/knowledge/entity/Document.java` | 文档数据库表 |
| **DTO** | `hify-knowledge/src/main/java/com/hify/knowledge/dto/ChunkVO.java` | 向量化后的 chunk 数据结构 |
| **在 Chat 中使用** | `hify-chat/src/main/java/com/hify/chat/service/impl/ChatServiceImpl.java` | `buildSystemPrompt()`, `searchChunks()` |

---

## 详细流程分析

### 1. 文档上传流程

#### 触发点
```java
DocumentVO uploadDocument(Long kbId, MultipartFile file)
```

**文件位置**: `KnowledgeServiceImpl.java` 第 97-154 行

#### 具体步骤

```
Step 1: 文件验证
├─ 检查文件类型：允许 txt, md, pdf
├─ 检查文件大小：最大 10MB
└─ 拒绝不符合的文件 → 抛出异常

Step 2: 读取文件内容
├─ txt/md → 用 UTF-8 解码为文本
├─ pdf → Mock 模式返回占位符 "[PDF 文件：xxx]"
└─ 保存原始文本到内存 (DOC_RAW_TEXT)

Step 3: 写数据库记录
├─ 创建 Document 实体
├─ 设置状态为 "PENDING"
├─ chunkCount 初始为 0
└─ 返回 DocumentVO 响应给前端

Step 4: 异步处理
└─ 调用 asyncExecutor.execute() → processDocument(docId)
   （不阻塞前端，后台处理）
```

#### 关键代码片段

```java
public DocumentVO uploadDocument(Long kbId, MultipartFile file) {
    // 1. 验证文件类型和大小
    String ext = originalName.substring(originalName.lastIndexOf('.') + 1).toLowerCase();
    if (!ALLOWED_TYPES.contains(ext)) {  // 允许列表：txt, md, pdf
        throw new BizException(ErrorCode.PARAM_ERROR);
    }
    if (file.getSize() > MAX_SIZE) {  // 10MB 限制
        throw new BizException(ErrorCode.PARAM_ERROR);
    }

    // 2. 读取文件内容
    String rawText = "";
    if ("txt".equals(ext) || "md".equals(ext)) {
        rawText = new String(file.getBytes(), StandardCharsets.UTF_8);  // UTF-8 解码
    } else {
        rawText = "[PDF 文件：" + originalName + "，Mock 模式暂不解析]";
    }

    // 3. 写数据库记录
    Document doc = new Document();
    doc.setKnowledgeBaseId(kbId);
    doc.setName(originalName);
    doc.setStatus("PENDING");
    documentMapper.insert(doc);

    // 4. 异步处理
    if (!rawText.isBlank()) {
        DOC_RAW_TEXT.put(docId, rawText);  // 暂存原始文本
    }
    asyncExecutor.execute(() -> processDocument(docId));  // 异步处理
}
```

---

### 2. 文档处理和分块流程（核心向量化逻辑）

#### 处理函数
```java
private void processDocument(Long documentId)
```

**文件位置**: `KnowledgeServiceImpl.java` 第 225-280 行

#### 处理流程图

```
processDocument(docId)
├─ STEP 1: 修改状态 → "PROCESSING"
│
├─ STEP 2: 模拟耗时操作 (Thread.sleep)
│
├─ STEP 3: 生成 Chunks（关键步骤）
│  ├─ 读取原始文本
│  ├─ 按段落分割（连续空行 \n{2,}）
│  └─ 为每个段落创建 ChunkVO
│
├─ STEP 4: Mock 存储 chunks
│  └─ MOCK_CHUNKS.put(docId, chunks)
│
├─ STEP 5: 更新数据库
│  ├─ doc.setChunkCount(chunks.size())
│  ├─ doc.setStatus("DONE")
│  └─ documentMapper.updateById(doc)
│
└─ STEP 6: 记录日志
   └─ log.info("文档处理完成 docId={}, chunks={}")
```

#### 详细代码分析

```java
private void processDocument(Long documentId) {
    Document doc = documentMapper.selectById(documentId);
    if (doc == null) return;
    try {
        // Step 1: 修改状态为处理中
        doc.setStatus("PROCESSING");
        documentMapper.updateById(doc);

        // Step 2: 模拟处理耗时（2-4秒）
        Thread.sleep(2000 + new Random().nextInt(2000));

        // Step 3: 从内存取出原始文本并分块
        String rawText = DOC_RAW_TEXT.remove(documentId);  // 从内存移除
        List<ChunkVO> chunks = new ArrayList<>();
        
        if (rawText != null && !rawText.isBlank()) {
            // 关键：按双换行符分割段落
            String[] paragraphs = rawText.split("\\n{2,}");  // 正则：2个或以上换行
            
            for (int i = 0; i < paragraphs.length; i++) {
                String para = paragraphs[i].trim();
                if (para.isBlank()) continue;  // 跳过空段落
                
                // 创建 ChunkVO
                ChunkVO c = new ChunkVO();
                c.setId((long) (documentId * 1000 + i));
                c.setDocumentId(documentId);
                c.setChunkIndex(i);
                c.setContent(para);  // 段落内容
                c.setTokenCount(para.length() / 2 + 1);  // Mock token 计算
                chunks.add(c);
            }
        }

        // 如果没有内容（PDF 或空文件），创建占位 chunk
        if (chunks.isEmpty()) {
            ChunkVO c = new ChunkVO();
            c.setId(documentId * 1000);
            c.setDocumentId(documentId);
            c.setChunkIndex(0);
            c.setContent(String.format("【%s】（Mock 模式：文档内容未能解析）", doc.getName()));
            c.setTokenCount(20);
            chunks.add(c);
        }

        // Step 4: Mock 存储 chunks（真实场景是向量数据库 pgvector）
        int chunkCount = chunks.size();
        MOCK_CHUNKS.put(documentId, chunks);

        // Step 5: 更新数据库
        doc.setChunkCount(chunkCount);
        doc.setStatus("DONE");
        documentMapper.updateById(doc);

        log.info("文档处理完成 docId={}, chunks={}", documentId, chunkCount);
        
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    } catch (Exception e) {
        // 处理失败
        doc.setStatus("FAILED");
        doc.setErrorMessage(e.getMessage());
        documentMapper.updateById(doc);
    }
}
```

#### 关键参数解释

| 参数 | 含义 | 示例 |
|------|------|------|
| **chunkIndex** | 段落序号 | 0, 1, 2, ... |
| **tokenCount** | 令牌数（近似） | `content.length / 2 + 1` |
| **documentId * 1000 + i** | Chunk ID 生成规则 | docId=5, chunk0的ID=5000 |
| **\n{2,}** | 分割正则 | 2个或以上换行符 |

---

### 3. RAG 检索流程

#### 检索函数
```java
List<ChunkVO> searchChunks(Long knowledgeBaseId, String query, int topK)
```

**文件位置**: `KnowledgeServiceImpl.java` 第 197-223 行

#### 检索流程

```
searchChunks(kbId, query, topK=3)
├─ Step 1: 查询该知识库下所有状态为 "DONE" 的文档
│
├─ Step 2: 收集所有文档的 chunks
│  └─ 从 MOCK_CHUNKS 中读取每个文档的 chunk 列表
│
├─ Step 3: Mock 相似度排序
│  ├─ 使用 query 的 hashCode 作为随机种子
│  ├─ 打乱 chunks 列表
│  └─ （真实场景：计算向量余弦距离排序）
│
├─ Step 4: 取前 topK 条
│  └─ all.stream().limit(topK).toList()
│
└─ Step 5: 返回结果
   └─ 最多 topK 条最相关的 chunks
```

#### 代码实现

```java
@Override
public List<ChunkVO> searchChunks(Long knowledgeBaseId, String query, int topK) {
    // Step 1: 获取该知识库下所有已完成的文档
    List<Document> docs = documentMapper.selectList(
        new LambdaQueryWrapper<Document>()
            .eq(Document::getKnowledgeBaseId, knowledgeBaseId)
            .eq(Document::getStatus, "DONE"));

    // Step 2: 收集所有 chunks
    List<ChunkVO> all = new ArrayList<>();
    for (Document doc : docs) {
        List<ChunkVO> chunks = MOCK_CHUNKS.getOrDefault(doc.getId(), List.of());
        all.addAll(chunks);
    }

    // Step 3: Mock 相似度排序（真实场景用 pgvector 计算余弦距离）
    java.util.Collections.shuffle(all, new Random(query.hashCode()));

    // Step 4: 取前 topK 条
    List<ChunkVO> result = all.stream().limit(topK).toList();
    
    log.info("RAG mock 检索 kbId={} query='{}' 命中 {}/{} 条", 
             knowledgeBaseId, query, result.size(), all.size());
    
    return result;
}
```

#### Mock vs 真实实现对比

| 功能 | Mock 实现（现状） | 真实实现 |
|------|-----------------|--------|
| **存储** | 内存 `ConcurrentHashMap` | PostgreSQL + pgvector 扩展 |
| **分块** | 按段落（双换行） | 按单位长度 + 滑动窗口 |
| **向量化** | 无（直接文本） | 调用 Embedding API（OpenAI/Ollama） |
| **相似度计算** | 随机打乱 | 向量余弦距离 |
| **检索时间** | O(n) 打乱 | O(1) pgvector 索引查询 |
| **分布式** | 单机内存 | 支持分布式 |

---

## 数据库表结构

### Document 表

```java
@TableName("document")
public class Document extends BaseEntity {
    private Long knowledgeBaseId;      // 所属知识库 ID
    private String name;               // 文件名
    private String fileType;           // 文件类型 (txt/md/pdf)
    private Long fileSize;             // 文件大小（字节）
    private String status;             // 状态：PENDING/PROCESSING/DONE/FAILED
    private Integer chunkCount;        // chunk 数量
    private String errorMessage;       // 处理失败原因
}
```

### ChunkVO (DTO，不在数据库中)

```java
public class ChunkVO {
    private Long id;                   // chunk 唯一 ID
    private Long documentId;           // 所属文档 ID
    private Integer chunkIndex;        // 段落序号
    private String content;            // 段落内容（text）
    private Integer tokenCount;        // 令牌数（近似）
}
```

---

## 在 Chat 中如何使用

### 触发 RAG 的代码位置

**文件**: `hify-chat/src/main/java/com/hify/chat/service/impl/ChatServiceImpl.java`

#### 1. 检查 Agent 是否启用 RAG

```java
// 第 6.5 行（RAG 检索）
if (agent.getKnowledgeBaseId() != null) {
    ragChunks = knowledgeService.searchChunks(
        agent.getKnowledgeBaseId(),  // 知识库 ID
        userContent,                  // 用户问题作为 query
        3                             // 取前 3 条相关资料
    );
    log.info("action=rag_retrieve sessionId={} kbId={} hits={}", 
             sessionId, agent.getKnowledgeBaseId(), ragChunks.size());
}
```

#### 2. 将 chunks 加入 System Prompt

```java
// 调用 buildMessages()，传入 ragChunks
List<com.hify.provider.dto.ChatMessage> messages = buildMessages(
    agent.getSystemPrompt(),  // Agent 的基础 prompt
    ragChunks,                // RAG 检索到的资料
    contextMsgs,              // 历史对话上下文
    userContent               // 用户当前问题
);

// buildMessages() 内部调用 buildSystemPrompt()
// buildSystemPrompt() 将 ragChunks 追加到 System Prompt
```

#### 3. 完整数据流

```
用户问题 (userContent)
    ↓
doStreamChat()
    ├─ 检查 Agent.knowledgeBaseId 是否为 null
    │   ├─ null → 无 RAG，skip
    │   └─ not null → 执行 searchChunks()
    │
    └─ searchChunks(knowledgeBaseId, userContent, topK=3)
        ├─ 查询所有 DONE 状态的文档
        ├─ 收集 chunks
        ├─ Mock 排序（取前 3）
        └─ 返回 List<ChunkVO>
            
            ↓
buildMessages(systemPrompt, ragChunks, ...)
    └─ buildSystemPrompt(systemPrompt, ragChunks)
        ├─ systemPrompt = Agent 的基础 prompt
        ├─ 追加 RAG 指示和资料
        └─ 最终 System Prompt 包含：
            1. Agent 原始 prompt
            2. RAG 指示："请基于以下参考资料..."
            3. 【参考资料】
            4. [1] chunk1 内容
            5. [2] chunk2 内容
            6. [3] chunk3 内容
            
            ↓
消息列表发送给大模型
    [System Prompt] → [历史消息] → [当前问题]
```

---

## 数据示例

### 上传文件示例

```
文件名: knowledge.txt
内容:
---
Python 是一种高级编程语言。

它具有简单的语法和强大的库。

Python 广泛用于数据分析和机器学习。
---
```

### 处理后生成的 Chunks

```java
MOCK_CHUNKS = {
    5: [
        ChunkVO(id=5000, chunkIndex=0, content="Python 是一种高级编程语言。", tokenCount=20),
        ChunkVO(id=5001, chunkIndex=1, content="它具有简单的语法和强大的库。", tokenCount=18),
        ChunkVO(id=5002, chunkIndex=2, content="Python 广泛用于数据分析和机器学习。", tokenCount=22)
    ]
}
```

### RAG 检索示例

```
用户问题: "Python 用来做什么？"
searchChunks(kbId=1, query="Python 用来做什么？", topK=3)
返回: [chunk2, chunk0, chunk1]  // Mock 随机顺序

System Prompt 变成:
---
你是一个专业的编程助手。

请基于以下参考资料回答用户问题。
如果资料中没有相关信息，直接说「我没有找到相关资料」，不要编造。

【参考资料】
[1] Python 广泛用于数据分析和机器学习。
[2] Python 是一种高级编程语言。
[3] 它具有简单的语法和强大的库。
---
```

---

## 内存管理

### 两个重要的 Mock 存储

```java
// 1. 所有 chunks 的最终存储
private static final ConcurrentHashMap<Long, List<ChunkVO>> MOCK_CHUNKS = new ConcurrentHashMap<>();
// 键：documentId，值：该文档的所有 chunks

// 2. 上传后的原始文本暂存
private static final ConcurrentHashMap<Long, String> DOC_RAW_TEXT = new ConcurrentHashMap<>();
// 键：documentId，值：原始文件文本
// 处理后删除 remove(documentId)
```

### 生命周期

```
文件上传
    ↓
DOC_RAW_TEXT.put(docId, rawText)  ← 写入原始文本
    ↓
processDocument() 异步处理
    ├─ 从 DOC_RAW_TEXT.remove() 读取文本
    ├─ 分块生成 chunks
    └─ 写入 MOCK_CHUNKS
    
MOCK_CHUNKS 持久化到内存
    ↓
删除文档时 MOCK_CHUNKS.remove(docId)  ← 清理内存
```

---

## 与 LangChain 的对比

| 功能 | Hify | LangChain |
|------|------|----------|
| **文档加载** | `uploadDocument()` + 自定义 | 内置 Document Loader（PDF、Web、etc） |
| **分块策略** | 按段落（双换行） | 支持多种策略 + CharacterSplitter |
| **向量化** | Mock（无向量） | 集成 OpenAI/Ollama Embeddings |
| **存储** | 内存 Map | Pinecone/Weaviate/FAISS/pgvector |
| **检索** | 随机排序 | 向量相似度搜索 |
| **RAG 链** | 手动构建 | 内置 RetrievalQA / RAG 链 |

---

## 真实实现的改进方向

目前 Hify 使用 Mock 实现，完整的向量化系统需要：

1. **文档解析**
   - PDF 文本提取（PyPDF2 / pdfplumber）
   - Office 文档解析（python-docx）
   - 网页爬虫（BeautifulSoup）

2. **智能分块**
   ```python
   # 智能分块算法
   - 按固定长度分块（512 tokens）
   - 滑动窗口重叠（50% overlap）
   - 保持句子完整性
   ```

3. **向量化**
   ```
   使用 Embedding API:
   - OpenAI Embeddings
   - Ollama Embeddings (本地)
   - 开源模型 (Sentence Transformers)
   ```

4. **向量存储**
   ```sql
   -- PostgreSQL + pgvector
   CREATE TABLE chunks (
       id BIGINT PRIMARY KEY,
       document_id BIGINT,
       content TEXT,
       embedding vector(1536),  -- OpenAI embedding 维度
       created_at TIMESTAMP
   );
   CREATE INDEX ON chunks USING IVFFLAT (embedding vector_cosine_ops);
   ```

5. **相似度搜索**
   ```sql
   SELECT * FROM chunks 
   WHERE document_id IN (...)
   ORDER BY embedding <-> query_embedding
   LIMIT topK;
   ```

---

## 速查表

| 需求 | 代码位置 | 关键方法 |
|------|--------|--------|
| 上传文档 | KnowledgeServiceImpl.java | `uploadDocument()` (第 97 行) |
| 处理分块 | KnowledgeServiceImpl.java | `processDocument()` (第 225 行) |
| 检索资料 | KnowledgeServiceImpl.java | `searchChunks()` (第 197 行) |
| 在对话中使用 | ChatServiceImpl.java | `doStreamChat()` 第 6.5 行 + `buildSystemPrompt()` |
| 数据结构 | ChunkVO.java | 5 个字段：id, documentId, chunkIndex, content, tokenCount |

---

## 总结

### 当前状态（Mock 实现）
- ✅ 文档上传和分块逻辑完整
- ✅ 可以检索 chunks 并用于 RAG
- ❌ 无真实向量化（无 Embedding）
- ❌ 相似度计算是随机的（非向量距离）
- ❌ 存储在内存中（重启丢失）

### 核心流程
```
上传文档 → 异步分块 → Mock 存储 → 用户对话触发 RAG → 检索 chunks → 加入 System Prompt → 发送给大模型
```

### 关键优化点
- 真实的 Embedding 模型集成
- PostgreSQL + pgvector 存储
- 向量相似度搜索
- 智能分块和重叠处理
