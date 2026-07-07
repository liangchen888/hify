# Hify 对话请求流程分析

## 整体流程图

```
前端 UI
  ↓
POST /api/v1/chat/sessions/{sessionId}/messages/stream
  ↓
ChatController.streamMessage()
  ↓
ChatService.streamChat() [返回 SseEmitter]
  ├─ 异步线程 (llmExecutor)
  │   ├─ doStreamChat() [核心逻辑]
  │   ├─ LLM 第一轮调用 (adapter.streamChat)
  │   ├─ MCP 工具调用 (if tool_calls)
  │   ├─ LLM 第二轮调用 (adapter.streamChat)
  │   └─ 保存消息 + SSE 推送
```

## 三个关键阶段详解

### 第一阶段：请求初始化 & 上下文准备

**代码入口**：`ChatController.streamMessage()` (L54)

工作流：
```java
// 1. 参数校验 + 创建 SseEmitter
SseEmitter emitter = new SseEmitter(60_000L);

// 2. 异步执行：MdcTaskWrapper 传播 traceId、sessionId
llmExecutor.execute(MdcTaskWrapper.wrap(() -> {
    doStreamChat(sessionId, request.getContent(), emitter);
}));
```

**在 `doStreamChat()` 中** (L131-L183)：

| 操作 | 调用哪个工程模块 | 是否调 LLM | 说明 |
|------|----------------|---------|----|
| 加载 Session | hify-chat Mapper | ✗ | 从 MySQL 读取会话元数据 |
| 加载 Agent 配置 | hify-agent Mapper | ✗ | Redis 缓存(30min) 或 MySQL |
| 加载模型配置 | hify-provider Mapper | ✗ | 含 modelId、temperature 等 |
| 加载提供商 | hify-provider Mapper | ✗ | 含 apiKey、baseUrl 等 |
| 保存用户消息 | hify-chat Mapper | ✗ | INSERT 到 chat_message 表 |
| 加载上下文 | Redis / MySQL | ✗ | 最近 N 轮对话(默认 10 轮×2) |
| **RAG 检索** | hify-knowledge | ✗ | 基于 query 向量检索(Mock) |
| **构建消息** | - | ✗ | System Prompt + RAG 结果 + Context |

**工作流代码片段**：
```java
// 第 5 步：保存用户消息
saveMessage(sessionId, "user", userContent, null, null, null);

// 第 6 步：加载上下文
List<Map<String, String>> contextMsgs = loadContext(sessionId, maxMsgs);
// 尝试 Redis，miss 则回源 MySQL

// 第 6.5 步：RAG 检索
List<ChunkVO> ragChunks = List.of();
if (agent.getKnowledgeBaseId() != null) {
    ragChunks = knowledgeService.searchChunks(...);
    // Mock 实现：随机打乱后取 topK
}

// 第 7 步：构建发送给 LLM 的消息列表
List<ChatMessage> messages = buildMessages(
    agent.getSystemPrompt(),  // Agent 自定义 prompt
    ragChunks,                // RAG 检索结果作为参考资料
    contextMsgs,              // 历史对话
    userContent               // 用户当前问题
);
```

### 第二阶段：LLM 第一轮调用 & 流式推送

**代码位置**：`doStreamChat()` L200-L230

```java
// 第 9 步：构建请求
ChatRequest chatRequest = ChatRequest.builder()
    .modelId(modelConfig.getModelId())
    .messages(messages)
    .temperature(agent.getTemperature())
    .maxTokens(agent.getMaxTokens())
    .tools(toolSchemas)  // 包含 MCP 工具定义（如果 Agent 绑定了）
    .build();

// 第 10 步：调用 LLM 提供商适配器
ProviderAdapter adapter = adapterFactory.get(provider.getType());
ChatResponse llmResp;
try {
    llmResp = adapter.streamChat(provider, chatRequest, delta -> {
        // ← 每收到一个 delta（文本碎片）就立即推送给前端
        String event = objectMapper.writeValueAsString(
            Map.of("type", "delta", "content", delta)
        );
        emitter.send(SseEmitter.event().data(event));
    });
} catch (Exception e) {
    // 熔断、重试、超时等异常处理
}
```

**LLM 调用的核心对象**（Provider 模块）：

- `ProviderAdapterFactory` (L28): 工厂方法选择具体实现
- `OpenAiAdapter` / `AnthropicAdapter` / `OllamaAdapter` 等
- 每个 Adapter 实现 `streamChat()` 方法

**流式推送机制**：
- 使用 `SseEmitter.send()`
- 消费方（前端）订阅 `/api/v1/chat/sessions/{sessionId}/messages/stream`
- 一旦 LLM 返回数据，立即推送（低延迟体验）

### 第三阶段：Tool Calling & 第二轮 LLM 调用

**代码位置**：`doStreamChat()` L231-L300

**触发条件**：`llmResp.getFinishReason() == "tool_calls"` 且 `llmResp.getToolCalls()` 不为空

```java
if ("tool_calls".equals(llmResp.getFinishReason())) {
    log.info("发现工具调用，数量: {}", llmResp.getToolCalls().size());
    
    // 第 11 步：对每个工具调用执行
    for (ChatResponse.ToolCall toolCall : llmResp.getToolCalls()) {
        String toolResult = executeToolCall(toolCall, agent.getId());
        
        // executeToolCall() 的逻辑：
        // 1. 从 agentToolMapper 查询 Agent 绑定的 MCP Server
        // 2. 逐个尝试在这些 Server 上调用工具
        // 3. 调用 mcpService.callTool()
    }
    
    // 第 12 步：构建第二轮请求（包含工具结果）
    ChatRequest secondReq = ChatRequest.builder()
        .messages(messages + [assistant msg + tool results])
        .tools(toolSchemas)  // 第二轮也要携带
        .build();
    
    // 第 13 步：第二轮 LLM 调用
    llmResp = adapter.streamChat(provider, secondReq, delta -> {
        // 再次流式推送文本
    });
}
```

**工具调用的完整链路**：

```
executeToolCall(toolCall, agentId)
  ├─ agentToolMapper.selectMcpServerIdsByAgentId(agentId)
  │  └─ 查询 Agent 绑定的所有 MCP Server ID
  │
  ├─ for each mcpServerId:
  │   └─ mcpService.callTool(mcpServerId, toolName, arguments)
  │      ├─ McpServerMapper.selectById(mcpServerId)
  │      ├─ DirectMcpClient client = new DirectMcpClient(endpoint)
  │      ├─ client.initialize()
  │      └─ client.callTool(toolName, arguments)
  │         └─ 通过 HTTP/stdio 调用外部 MCP Server
  │
  └─ 返回 toolResult (String)
```

**工具 Schema 的加载** (L184-L194)：

```java
private List<Map<String, Object>> buildToolSchemas(Long agentId) {
    // 1. 查询 Agent 绑定的 MCP Server
    List<Long> mcpServerIds = agentToolMapper.selectMcpServerIdsByAgentId(agentId);
    
    // 2. 对每个 Server 加载工具列表（含参数定义）
    for (Long serverId : mcpServerIds) {
        List<McpToolDetail> toolDetails = mcpService.listToolsDetail(serverId);
        // toolDetails 包含：name、description、inputSchema
        
        // 3. 转换为 OpenAI tools format
        schemas.add(Map.of(
            "type", "function",
            "function", Map.of(
                "name", detail.getName(),
                "description", detail.getDescription(),
                "parameters", detail.getInputSchema()  // JSON Schema
            )
        ));
    }
    return schemas;
}
```

## 大模型判断能力 vs 工程调用

### 🧠 大模型的职责（LLM 决策）：

1. **理解用户问题** → 提取意图
2. **选择工具** → 根据 tools schema，决定是否需要调用工具（Tool Calling）
3. **生成回答** → 基于工具结果或直接回答

### 🏗️ 工程的职责（应用逻辑）：

| 任务 | 谁负责 | 模块 | 说明 |
|------|-------|------|------|
| 解析 JSON 请求 | 工程 | hify-chat | 参数校验 |
| 加载 Agent 配置 | 工程 | hify-agent | 包含绑定的模型、工具、Prompt |
| 构建消息列表 | 工程 | hify-chat | 含 System Prompt、上下文、RAG |
| **调用 LLM API** | **LLM** | hify-provider | 工程只负责封装、重试、熔断 |
| **LLM 选择工具** | **LLM** | - | LLM 自主决策 finish_reason |
| 执行工具调用 | 工程 | hify-mcp | 根据 LLM 的 toolCall 指令执行 |
| 第二轮 LLM 调用 | **LLM** | hify-provider | 基于工具结果生成最终回答 |
| 保存对话记录 | 工程 | hify-chat | INSERT 到 MySQL + Redis cache |

## 特殊分支：Workflow 执行

**条件**：`Agent.workflowId != null`

```java
// 代码位置：doStreamChat() L152-L171
if (agent.getWorkflowId() != null) {
    String wfOutput = workflowEngine.execute(
        agent.getWorkflowId(),
        userContent  // 用户问题作为输入
    );
    // 跳过 LLM 直接走工作流引擎
    // 工作流内部可能调用 LLM 节点、条件分支等
}
```

**工作流引擎的执行** (WorkflowEngine.java)：

```
WorkflowEngine.execute(workflowId, userMessage)
  ├─ 1. 加载工作流配置（节点 + 边）
  ├─ 2. 创建执行记录 (WorkflowRun)
  ├─ 3. 找 START 节点，开始循环
  ├─ 4. 主循环（最多 50 步）：
  │   ├─ if 节点类型 == "LLM"
  │   │  └─ 调用 LLMNodeExecutor (内部调 Provider 适配器)
  │   ├─ if 节点类型 == "CONDITION"
  │   │  └─ 执行表达式，决定分支
  │   ├─ if 节点类型 == "TOOL_CALL"
  │   │  └─ 调用 MCP 工具
  │   └─ 根据边的条件（conditionExpr）选择下一节点
  ├─ 5. 到达 END 节点
  └─ 6. 返回输出
```

## RAG 流程详解

**代码位置**：`doStreamChat()` L176-L179 和 `KnowledgeServiceImpl.searchChunks()`

```java
// 触发 RAG 的条件
if (agent.getKnowledgeBaseId() != null) {
    ragChunks = knowledgeService.searchChunks(
        agent.getKnowledgeBaseId(),  // 知识库 ID
        userContent,                  // 用户问题（作为查询语义）
        3                             // topK = 3
    );
}
```

**Mock 实现的 RAG 检索**：

```java
// KnowledgeServiceImpl.searchChunks()
List<Document> docs = documentMapper.selectList(
    new LambdaQueryWrapper<Document>()
        .eq(Document::getKnowledgeBaseId, knowledgeBaseId)
        .eq(Document::getStatus, "DONE")  // 只检索处理完毕的文档
);

// Mock 相似度排序（真实应调用 Embedding API + pgvector）
List<ChunkVO> all = new ArrayList<>();
for (Document doc : docs) {
    all.addAll(MOCK_CHUNKS.get(doc.getId()));  // 内存存储
}
Collections.shuffle(all, new Random(query.hashCode()));
return all.stream().limit(topK).toList();
```

**RAG 结果的融入**：

```java
// buildSystemPrompt() 拼接 System Prompt
private String buildSystemPrompt(String agentPrompt, List<ChunkVO> chunks) {
    StringBuilder sb = new StringBuilder();
    if (agentPrompt != null) sb.append(agentPrompt).append("\n\n");
    
    sb.append("请基于以下参考资料回答用户问题。\n");
    sb.append("如果资料中没有相关信息，直接说「我没有找到相关资料」\n\n");
    sb.append("【参考资料】\n");
    for (int i = 0; i < chunks.size(); i++) {
        sb.append("[").append(i + 1).append("] ")
          .append(chunks.get(i).getContent()).append("\n");
    }
    return sb.toString();
}
```

## 缓存策略

| 数据 | 存储 | TTL | 策略 |
|------|------|-----|------|
| Agent 配置 | Redis | 30min | Cache-Aside |
| Provider 配置 | Redis | 30min | Cache-Aside |
| 对话上下文 | Redis | 2h | Rolling window |
| 对话消息 | MySQL | - | 持久化 |
| MCP 工具列表 | 内存 | - | 连接时加载 |
| 知识库 Chunks | 内存 (Mock) | - | ProcessDocument 时加载 |

**上下文加载逻辑**：

```java
private List<Map<String, String>> loadContext(Long sessionId, int maxMsgs) {
    String key = "session:" + sessionId;
    
    // Step 1: 尝试 Redis
    if (redisUtil.isPresent()) {
        Optional<List<Map>> cached = redisUtil.get().get(key);
        if (cached.isPresent()) return cached.get();
    }
    
    // Step 2: Miss，回源 MySQL
    List<ChatMessage> dbMsgs = messageMapper.selectRecentBySessionId(
        sessionId,
        maxMsgs
    );
    
    // Step 3: 转换格式
    List<Map<String, String>> ctx = dbMsgs.stream()
        .map(m -> Map.of("role", m.getRole(), "content", m.getContent()))
        .collect(Collectors.toList());
    
    // Step 4: 写回 Redis
    redisUtil.ifPresent(r -> r.set(key, ctx, SESSION_TTL));
    
    return ctx;
}
```

## 错误处理与熔断

**LLM 调用的 Resilience4j 熔断策略**：

- **slidingWindowSize**: 10  (最近 10 次调用)
- **failureRateThreshold**: 50%  (失败率达 50% 就打开熔断)
- **waitDurationInOpenState**: 30s  (熔断器打开 30s 后尝试恢复)

**重试策略**（按异常类型）：

| 异常类型 | 是否重试 | 说明 |
|---------|--------|------|
| 网络抖动 (IOException) | ✓ | 指数退避 |
| 认证失败 (401/403) | ✗ | 快速失败 |
| 限流 (429) | ✓ | 退避重试 |
| 超时 (60s for chat) | ✗ | 直接返回超时错误 |

## 前端调用

**前端入口** (hify-web)：

```typescript
// 流式对话
POST /api/v1/chat/sessions/{sessionId}/messages/stream
Content-Type: application/json

{
  "content": "用户问题",
  "stream": true
}

// 响应是 SSE 事件流
event: {"type": "delta", "content": "应..."}
event: {"type": "delta", "content": "该..."}
event: {"type": "delta", "content": "会..."}
...
event: {"type": "done", "finishReason": "stop", "latencyMs": 1234}
```

**同步对话**：

```typescript
POST /api/v1/chat/sessions/{sessionId}/messages
{
  "content": "用户问题",
  "stream": false
}

// 响应
{
  "code": 200,
  "data": {
    "id": 123,
    "role": "assistant",
    "content": "完整回答",
    "tokens": 256,
    "finishReason": "stop",
    "latencyMs": 1234
  }
}
```

## 关键指标与监控

**Metrics 收集**（HifyMetrics）：

```
- llmCallIncrement(providerType, modelId, success)
- llmCallDuration(providerType, modelId, durationMs)
- mcpToolCallIncrement(toolName, success)
- mcpToolCallDuration(toolName, durationMs)
- chatRequestIncrement(agentId)
- chatRequestDuration(agentId, durationMs)
```

**日志追踪**（MDC）：

```
- MDC_TRACE_ID: 请求链路 ID
- MDC_SESSION_ID: 对话会话 ID
- MDC_AGENT_ID: Agent ID
```

## 总结表格



### 每个步骤是工程还是大模型决策

| 步骤 | 操作 | 负责方 | 模块 | 说明 |
|------|------|--------|------|------|
| 1 | 创建 SSE 连接 | 工程 | hify-chat | HTTP 长连接 |
| 2 | 加载 Session | 工程 | hify-chat | 从数据库读 |
| 3 | 加载 Agent 配置 | 工程 | hify-agent | 获取模型、Prompt、工具、知识库 ID |
| 4 | 加载模型配置 | 工程 | hify-provider | 获取 modelId、temperature 等 |
| 5 | 加载提供商配置 | 工程 | hify-provider | 获取 apiKey、baseUrl、熔断器状态 |
| 6 | 保存用户消息 | 工程 | hify-chat | 持久化到 MySQL |
| 7 | 加载上下文 | 工程 | hify-chat / Redis | 检索最近 N 轮对话 |
| 8 | RAG 检索 | 工程 | hify-knowledge | 从知识库检索相关文档片段 |
| 9 | 构建消息列表 | 工程 | hify-chat | 组装 System + Context + User |
| 10 | **调用 LLM** | **大模型** | hify-provider | 发送 HTTP 请求到 LLM API |
| 11 | **LLM 生成文本** | **大模型** | - | 大模型推理，返回 delta |
| 12 | **流式推送文本** | 工程 | hify-chat | 通过 SSE 实时推送给前端 |
| 13 | **LLM 判断结束** | **大模型** | - | 返回 finish_reason (stop/tool_calls) |
| 14 | **LLM 决策工具** | **大模型** | - | 如果返回 tool_calls，LLM 已自主选择 |
| 15 | 执行工具调用 | 工程 | hify-mcp | 根据 LLM 指令调用外部工具 |
| 16 | **LLM 第二轮调用** | **大模型** | hify-provider | 基于工具结果继续推理 |
| 17 | 保存助手消息 | 工程 | hify-chat | 持久化完整回答到 MySQL |
| 18 | 更新 Redis 缓存 | 工程 | hify-chat | 滑动窗口更新上下文 |
| 19 | 发送完成事件 | 工程 | hify-chat | 推送 done 事件 + latency |

### 调用链路速查表

```
用户提问
    ↓
[工程] ChatController 接收
    ↓
[工程] 异步线程执行 (llmExecutor)
    ↓
[工程] 加载 Session / Agent / Provider
    ↓
[工程] RAG 检索相关文档
    ↓
[工程] 构建消息列表
    ↓
[大模型] LLM API 第一轮调用 ← 关键决策点 1：生成内容或返回 tool_calls
    ↓
    ├─ if finish_reason == "stop"
    │   ├─ [工程] 保存消息
    │   └─ [工程] SSE 推送完成事件
    │
    └─ if finish_reason == "tool_calls"
        ├─ [工程] 解析工具调用列表
        ├─ [工程] 执行每个工具 (MCP 调用)
        ├─ [大模型] LLM API 第二轮调用 ← 关键决策点 2：基于工具结果生成最终回答
        ├─ [工程] 保存消息
        └─ [工程] SSE 推送完成事件
```

## 实例：一次完整的对话请求

假设用户在前端发送问题："订单12345能退货吗？"

### 详细过程

**第 1-5 步：初始化**
```
时间: T+0
1. 前端: POST /api/v1/chat/sessions/999/messages/stream
   Body: {"content": "订单12345能退货吗？"}

2. 后端 ChatController: 接收请求
   ↓ 创建 SseEmitter
   ↓ 提交到 llmExecutor (背景线程)

3. ChatServiceImpl.doStreamChat() 开始执行
   ├─ 加载 Session 999 (Agent ID: 42)
   ├─ 加载 Agent 42 (modelConfigId: 7, workflowId: null, kbId: 3)
   ├─ 加载 ModelConfig 7 (modelId: "gpt-4", providerId: 2)
   ├─ 加载 Provider 2 (type: "OPENAI", apiKey: ****)
   └─ 保存用户消息 到 MySQL
       INSERT INTO chat_message
       VALUES (msgId, sessionId=999, role='user', 
               content='订单12345能退货吗？', ...)
```

**第 6-9 步：上下文构建**
```
时间: T+50ms
5. 加载上下文
   ├─ Redis.get("session:999")  → MISS
   └─ MySQL: SELECT * FROM chat_message 
             WHERE session_id=999 
             ORDER BY created_at DESC 
             LIMIT 20
       → 返回最近 10 条消息

6. RAG 检索 (kbId=3)
   ├─ SELECT documents 
      WHERE knowledge_base_id=3 AND status='DONE'
   ├─ 从内存 MOCK_CHUNKS 提取所有片段
   ├─ 随机打乱（Mock 相似度）
   └─ 返回 topK=3 的片段
       例：["退货政策：7天无理由...", "需要保留凭证...", "...]

7. 构建消息列表
   ├─ System: 
   │    "你是电商客服助手。\n
   │     请基于以下参考资料回答...\n
   │     【参考资料】\n
   │     [1] 退货政策：7天无理由...\n
   │     [2] 需要保留凭证...\n"
   ├─ 历史消息 (Context):
   │    {"role": "user", "content": "之前问题1"}
   │    {"role": "assistant", "content": "之前回答1"}
   │    {"role": "user", "content": "之前问题2"}
   │    {"role": "assistant", "content": "之前回答2"}
   └─ 当前消息:
        {"role": "user", "content": "订单12345能退货吗？"}

8. 加载工具 Schema
   ├─ SELECT mcp_server_ids FROM agent_tool
      WHERE agent_id=42
      → [101, 102]  (退款和物流 server)
   ├─ 从 MCP 101 加载工具:
   │    {"name": "check_refund_eligibility",
   │     "description": "检查订单是否符合退货资格",
   │     "parameters": {"type": "object", ...}}
   ├─ 从 MCP 102 加载工具:
   │    {"name": "track_logistics",
   │     "description": "查询物流信息", ...}
   └─ tools schema 列表已准备
```

**第 10-13 步：LLM 第一轮调用**
```
时间: T+100ms
9. 构建 ChatRequest
   {
     "model": "gpt-4",
     "messages": [...System, ...Context, ...User],
     "temperature": 0.7,
     "max_tokens": 2048,
     "tools": [
       {"type": "function", "function": {"name": "check_refund_eligibility", ...}},
       {"type": "function", "function": {"name": "track_logistics", ...}}
     ]
   }

10. 调用 LLM 提供商适配器
    ├─ adapterFactory.get("OPENAI") 
    │  → OpenAiAdapter 实例
    ├─ OpenAiAdapter.streamChat(provider, chatRequest, callback)
    │  ├─ 构建 HTTP 请求头 (Authorization: Bearer ***)
    │  ├─ POST https://api.openai.com/v1/chat/completions
    │  └─ 流式接收响应
    │     每个 chunk:
    │       "data: {"choices":[{"delta":{"content":"我"}}]}\n"
    │       onChunk("我")
    │         ↓ 
    │       emitter.send(SSE event with "我")
    │         ↓ 
    │       前端实时看到字符出现
    │
    └─ 返回 ChatResponse
       {
         "content": "根据退货政策，订单...",
         "finishReason": "tool_calls",
         "toolCalls": [
           {
             "id": "call_123",
             "name": "check_refund_eligibility",
             "arguments": "{\"orderId\": \"12345\"}"
           }
         ]
       }
```

**第 14-16 步：工具调用 & LLM 第二轮**
```
时间: T+500ms
LLM 返回了 tool_calls，说明 LLM 的 finish_reason="tool_calls"
↓
代码进入 if ("tool_calls".equals(llmResp.getFinishReason())) {...}

11. 执行工具调用
    ├─ for ToolCall in llmResp.getToolCalls():
    │    ├─ toolCall.name = "check_refund_eligibility"
    │    ├─ toolCall.arguments = "{\"orderId\": \"12345\"}"
    │    ├─ executeToolCall(toolCall, agentId=42)
    │    │  ├─ SELECT mcp_server_ids FROM agent_tool WHERE agent_id=42
    │    │  │  → [101, 102]
    │    │  ├─ for serverId in [101, 102]:
    │    │  │    try:
    │    │  │      mcpService.callTool(
    │    │  │        mcpServerId=101,
    │    │  │        toolName="check_refund_eligibility",
    │    │  │        arguments={"orderId": "12345"}
    │    │  │      )
    │    │  │      ├─ DirectMcpClient 连接到 MCP Server (endpoint)
    │    │  │      ├─ 调用 LLM/工具接口
    │    │  │      └─ 返回 result
    │    │  │
    │    │  │      例: "该订单符合7天无理由退货..."
    │    │  │    break  (成功了就不试下一个 server)
    │    │
    │    ├─ toolResult = "该订单符合7天无理由退货..."
    │    │
    │    ├─ 构建 tool result message
    │    │    {
    │    │      "role": "tool",
    │    │      "tool_call_id": "call_123",
    │    │      "content": "该订单符合7天无理由退货..."
    │    │    }
    │    │
    │    └─ 追加到 messages 列表

12. 构建第二轮 LLM 请求
    {
      "model": "gpt-4",
      "messages": [
        ...System,
        ...Context,
        {"role": "user", "content": "订单12345能退货吗？"},
        {"role": "assistant", "content": "", "tool_calls": [...]},
        {"role": "tool", "tool_call_id": "call_123", 
         "content": "该订单符合7天无理由退货..."}
      ],
      "tools": [...]  ← 第二轮仍需工具定义
    }

13. 第二轮 LLM 调用
    ├─ OpenAiAdapter.streamChat(provider, secondReq, callback)
    ├─ LLM 基于工具结果继续推理
    ├─ 逐字流式返回最终回答
    │    "该订单符合7天无理由退货，请注意..."
    └─ finishReason = "stop" (不再调用工具)
```

**第 17-19 步：收尾**
```
时间: T+1000ms
14. 保存助手消息
    INSERT INTO chat_message
    VALUES (
      msgId,
      sessionId=999,
      role='assistant',
      content='该订单符合7天无理由退货，请注意...',
      tokens=512,
      finish_reason='stop',
      latency_ms=1000
    )

15. 更新 Redis 缓存
    ctx = [
      {"role": "user", "content": "之前问题1"},
      {"role": "assistant", "content": "之前回答1"},
      ...
      {"role": "user", "content": "订单12345能退货吗？"},
      {"role": "assistant", "content": "该订单符合7天无理由退货..."}
    ]
    Redis.set("session:999", ctx, TTL=2h)

16. 发送完成事件
    emitter.send(SseEmitter.event().data({
      "type": "done",
      "finishReason": "stop",
      "latencyMs": 1000
    }))
    emitter.complete()

17. 记录指标
    metrics.llmCallIncrement("OPENAI", "gpt-4", true)
    metrics.llmCallDuration("OPENAI", "gpt-4", 500)
    metrics.mcpToolCallIncrement("check_refund_eligibility", true)
    metrics.mcpToolCallDuration("check_refund_eligibility", 250)
    metrics.chatRequestIncrement("42")  // agentId
    metrics.chatRequestDuration("42", 1000)
```

**前端收到的 SSE 事件序列**：
```
event: {"type": "delta", "content": "该"}
event: {"type": "delta", "content": "订"}
event: {"type": "delta", "content": "单"}
event: {"type": "delta", "content": "符"}
...
event: {"type": "done", "finishReason": "stop", "latencyMs": 1000}
```

---

## 架构设计的关键特点

### 1. 异步非阻塞流式处理
- ✓ 前端立即得到 SseEmitter，可以建立连接
- ✓ 后端在 llmExecutor 线程异步处理，不阻塞业务线程
- ✓ LLM 返回的每个 delta 立即推送，用户体验流畅

### 2. 清晰的职责分离
- **工程**：数据加载、消息构建、工具执行、持久化
- **大模型**：理解意图、生成内容、选择工具

### 3. 多轮 Agent 交互
- LLM 可决策是否需要工具
- 工程负责执行工具
- LLM 再次推理最终回答

### 4. 灵活的扩展点
- **工具层**：通过 MCP 接入任意外部工具
- **知识层**：通过 RAG 增强回答准确性
- **工作流层**：通过 Workflow 引擎支持复杂编排

### 5. 可靠性保障
- Redis 缓存 + MySQL 持久化双保险
- Resilience4j 熔断器防止级联故障
- MDC 链路追踪便于问题诊断
- 详细的日志和指标收集

