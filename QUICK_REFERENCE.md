# Hify 对话流程快速查询手册

## 🎯 一句话总结

**用户提问 → 工程加载配置 → LLM 第一轮生成 → (可选) 工程执行工具 → (可选) LLM 第二轮生成 → 保存+推送**

---

## 📋 核心问题速查

### Q1: 请求是如何从前端到达后端的？

**A:** 
```
前端 POST /api/v1/chat/sessions/{sessionId}/messages/stream
  ↓
ChatController.streamMessage() 接收
  ↓
返回 SseEmitter
  ↓
后端在 llmExecutor 异步线程执行 doStreamChat()
```

**关键：使用异步执行，前端立即得到连接，不阻塞。**

---

### Q2: 哪些操作是从工程的数据库/缓存里读的？

**A:** 这些 **NOT LLM**：

| 操作 | 存储 | TTL | 代码行 |
|------|------|-----|--------|
| 加载 Session | MySQL | - | L148 |
| 加载 Agent 配置 | Redis/MySQL | 30min | L151 |
| 加载 ModelConfig | Redis/MySQL | 30min | L154 |
| 加载 Provider | Redis/MySQL | 30min | L157 |
| 保存用户消息 | MySQL | - | L177 |
| 加载历史消息 | Redis/MySQL | 2h | L182 |
| RAG 检索 | 内存 | - | L185 |
| 查询 MCP Server | MySQL | - | L194 |

**代码文件：** `hify-chat/src/main/java/com/hify/chat/service/impl/ChatServiceImpl.java`

---

### Q3: LLM 被调用了几次？

**A:**
- **第 1 次 (必须)**: `adapter.streamChat()` L208
  - 输入：System + Context + User Message + Tools
  - 输出：文本 + finish_reason + (可选) toolCalls

- **第 2 次 (可选)**: `adapter.streamChat()` L269 (仅当第 1 次返回 tool_calls)
  - 输入：第一次的所有消息 + assistant 回复 + tool 结果
  - 输出：最终回答

---

### Q4: 工具调用是怎么触发的？

**A:** **LLM 决策，工程执行**

```
1. LLM 分析用户问题
2. LLM 决定是否需要工具 (基于 tools schema)
3. LLM 返回 "finish_reason": "tool_calls"
4. 工程收到指令，执行工具
5. 工程把工具结果喂给 LLM
6. LLM 继续推理
```

**代码：** L231-L300 在 ChatServiceImpl

**工具执行链路：**
```
executeToolCall(toolCall, agentId)
  └─ for each MCP Server bound to Agent:
      └─ mcpService.callTool(serverId, toolName, arguments)
         └─ DirectMcpClient.callTool(...)
            └─ HTTP/stdio 调用外部工具
```

---

### Q5: RAG 是什么时候起作用的？

**A:** **对话初始化，在构建 System Prompt 时**

```
if (agent.getKnowledgeBaseId() != null) {
    List<ChunkVO> chunks = knowledgeService.searchChunks(
        agent.getKnowledgeBaseId(),
        userContent,    // ← 用户问题作为查询
        3               // topK
    );
}

// 然后拼接到 System Prompt
finalSystem = buildSystemPrompt(agentPrompt, chunks);
// 【参考资料】
// [1] 文档片段1
// [2] 文档片段2
// [3] 文档片段3
```

**代码行：** L176-L179, L339-L360

**Mock 实现：** 从内存中随机取 topK 条（真实应用是向量相似度排序）

---

### Q6: 前端怎么看到实时的打字效果？

**A:** **SSE (Server-Sent Events) 流式推送**

```java
llmResp = adapter.streamChat(provider, chatRequest, delta -> {
    // 每收到一个 delta（文本碎片）：
    String event = objectMapper.writeValueAsString(
        Map.of("type", "delta", "content", delta)
    );
    emitter.send(SseEmitter.event().data(event));
    // ↑ 前端立即收到，显示一个字符
});
```

**前端接收：**
```javascript
const source = new EventSource('/api/v1/chat/sessions/999/messages/stream');
source.addEventListener('message', (event) => {
    const data = JSON.parse(event.data);
    if (data.type === 'delta') {
        displayText(data.content);  // 逐字显示
    }
    if (data.type === 'done') {
        source.close();  // 结束
    }
});
```

---

### Q7: 大模型和工程分别控制什么？

**A:** 清晰的职责边界

| 阶段 | 工程 | 大模型 |
|------|------|--------|
| **1. 准备** | ✓ 加载配置、构建消息 | - |
| **2. 第一次调用** | ✓ 发送请求 | ✓ 生成内容/选择工具 |
| **3. 工具执行** | ✓ 调用外部工具 | - |
| **4. 第二次调用** | ✓ 发送请求 | ✓ 基于工具结果生成答案 |
| **5. 保存** | ✓ 存到数据库 | - |

**不是工程决定用不用工具，而是 LLM 看了 tools schema 后自己决定。**

---

### Q8: 缓存策略是什么？

**A:** **三层缓存 + 回源**

```
Layer 1 (Redis) ← 最快
  ├─ Agent 配置 (TTL: 30min)
  ├─ Provider 配置 (TTL: 30min)
  ├─ 对话上下文 (TTL: 2h)
  └─ Miss → Layer 2

Layer 2 (MySQL) ← 可靠
  ├─ 配置表查询
  ├─ 历史消息查询
  └─ Write-back to Redis

Layer 3 (内存) ← 某些特殊数据
  ├─ MCP 工具列表 (每次连接时加载)
  ├─ 知识库 Chunks (Mock)
```

**代码：** loadContext() L523-L540

---

### Q9: 如果 LLM API 失败了怎么办？

**A:** **熔断器 + 重试**

**Resilience4j 熔断配置：**
```
slidingWindowSize: 10      // 最近 10 次调用
failureRateThreshold: 50%  // 失败率 ≥ 50% 就打开
waitDurationInOpenState: 30s  // 30s 后尝试恢复
```

**重试策略：**
- 网络抖动（IOException）→ 指数退避重试
- 认证失败（401/403）→ 快速失败，不重试
- 限流（429）→ 退避重试
- 超时（60s）→ 直接返回超时错误

**代码：** L208-L230 (try-catch 处理)

---

### Q10: 同步对话（非流式）和流式对话有什么区别？

**A:**

| 方面 | 流式 | 同步 |
|------|------|------|
| 接口 | POST .../messages/stream | POST .../messages |
| 返回 | SseEmitter | Result<MessageResp> |
| 前端体验 | 实时打字效果 | 等待完整回答后一次返回 |
| 实现 | adapter.streamChat() + callback | adapter.chat() |
| 代码 | doStreamChat() L131-L326 | syncChat() L329-L380 |

**选择建议：**
- 用户交互场景 → 流式（更友好）
- 工作流内部调用 → 同步（更简单）

---

## 🔍 常见问题诊断

### 问题：为什么消息没有流式出现？
**排查：**
1. 检查前端是否用了 EventSource 监听 SSE
2. 检查 Nginx 配置中 `proxy_buffering off`（否则缓冲整个响应）
3. 检查 `emitter.send()` 是否真的被调用了（日志）

### 问题：工具调用失败
**排查：**
1. Agent 是否绑定了 MCP Server？ → 查 agent_tool 表
2. MCP Server 是否在线？ → 调用 testConnection
3. 工具名称是否正确？ → 检查 MCP Server 的 listToolsDetail()

### 问题：RAG 检索为空
**排查：**
1. Agent.knowledgeBaseId 是否设置？
2. 知识库中是否有状态为 DONE 的文档？
3. Mock 相似度排序是否有问题？→ 改用真实向量数据库

### 问题：对话上下文丢失
**排查：**
1. Redis 是否连接正常？
2. SESSION_TTL (2h) 是否过期了？
3. MySQL 中的历史消息是否被删除了？

---

## 📊 性能指标

### 典型响应时间分解

```
总耗时: 1000ms
├─ 初始化 (加载配置): 50ms
├─ RAG 检索: 30ms
├─ 构建消息: 20ms
├─ LLM 第一轮: 500ms  ← 占大头
├─ 工具执行: 250ms  ← 如果有
├─ LLM 第二轮: 120ms  ← 如果有
└─ 保存 + 其他: 30ms
```

### 可扩展性

- **25 人同时对话** → 单 Java 进程 OK
- **50 人同时对话** → 考虑多个 chat 实例 (负载均衡)
- **大量工具调用** → MCP Server 做负载均衡

---

## 🛠️ 代码导航

### 流程入口
```
hify-chat/controller/ChatController.java
  └─ streamMessage() L54
     └─ streamChat() L48
        └─ ChatServiceImpl.doStreamChat() L131
```

### 关键模块

| 模块 | 文件 | 职责 |
|------|------|------|
| hify-chat | ChatServiceImpl | 对话核心逻辑 |
| hify-provider | ProviderAdapter | LLM 适配器 |
| hify-mcp | McpServiceImpl | 工具执行 |
| hify-knowledge | KnowledgeServiceImpl | RAG 检索 |
| hify-workflow | WorkflowEngine | 工作流编排 |
| hify-agent | AgentServiceImpl | Agent 配置管理 |

### 重要类

```
ChatResponse           // LLM 响应对象
├─ content: String
├─ finishReason: String (stop / tool_calls)
├─ toolCalls: List<ToolCall>
│  └─ id, name, arguments
└─ completionTokens: int

ChatRequest           // 发送给 LLM 的请求
├─ modelId: String
├─ messages: List<ChatMessage>
├─ temperature: Double
├─ maxTokens: int
└─ tools: List<Map>  (OpenAI tools format)

SseEmitter           // SSE 连接对象
├─ send(event)       // 推送事件
├─ complete()        // 结束连接
└─ onTimeout / onError
```

---

## 🎓 学习路径

1. **快速了解** → 读这个文档 + FLOW_DIAGRAM.txt
2. **深入理解** → 读 ChatServiceImpl.doStreamChat() 全部代码
3. **实战修改** → 
   - 改 System Prompt 构建逻辑
   - 添加新的 RAG 过滤
   - 自定义工具执行流程
4. **性能优化** →
   - 缓存命中率分析
   - 工具调用并行化
   - LLM 流响应优化

---

## ✅ 检查清单

启动应用前：
- [ ] Redis 是否运行？
- [ ] MySQL 是否运行？
- [ ] LLM API Key 是否配置？
- [ ] MCP Server 是否启动？

添加新工具时：
- [ ] MCP Server 的工具是否实现了？
- [ ] Agent 是否绑定了 MCP Server？
- [ ] 工具 Schema (description + parameters) 是否完整？
- [ ] 测试连通性？ → POST /api/v1/mcp-servers/{id}/test

添加新知识库时：
- [ ] 文档是否上传成功？
- [ ] 文档状态是否为 DONE？
- [ ] Agent.knowledgeBaseId 是否指向正确的库？
- [ ] RAG 检索是否返回相关片段？

---

## 📞 问题咨询

**不清楚的地方对照这个表：**

| 问题类型 | 查看文件 | 关键行数 |
|---------|---------|---------|
| 消息流如何推送 | ChatServiceImpl.java | 220-230 |
| 工具怎么执行 | ChatServiceImpl.java | 236-280 |
| RAG 怎么检索 | KnowledgeServiceImpl.java | 174-198 |
| 缓存怎么更新 | ChatServiceImpl.java | 300-315 |
| MCP 怎么加载 | ChatServiceImpl.java | 184-200 |
| 工作流怎么执行 | WorkflowEngine.java | 全部 |

