# hify 中的 Temperature 参数详解

## 概述
`temperature` 是 hify 中控制大模型**创意度**和**确定性**的关键参数。它影响大模型生成回复时的「随意性」程度。

---

## Temperature 是什么？

### 定义
Temperature 是一个浮点数参数，范围 **0.00 到 1.00**，控制大模型的**文本生成随机性**：

- **温度低** (接近 0.0)：模型倾向选择概率最高的词，输出**更稳定、更确定**
- **温度高** (接近 1.0)：模型会尝试更多不同的词，输出**更多样、更有创意**

### 物理类比
就像「加热」一个系统会增加熵值，高温度让模型「思想更活跃」，低温度让模型「更冷静、更专注」。

---

## hify 中的 Temperature 实现

### 1. **数据库定义**
在 `Agent` 表中存储：
```sql
-- hify-app/src/main/resources/db/schema.sql
temperature DECIMAL(3,2) NOT NULL DEFAULT 0.70
-- 范围限制在 [0.00, 1.00]
```

### 2. **Java 实体定义**
在 `Agent.java` 中：
```java
@Getter
@Setter
@TableName("agent")
public class Agent extends BaseEntity {
    // ...
    /** 0.00~1.00 */
    private BigDecimal temperature;
    // ...
}
```

**默认值**：0.70（中等创意度）

### 3. **验证约束**
在创建/更新 Agent 时：
```java
@DecimalMin("0.00")
@DecimalMax("1.00")
```
确保 temperature 值在合法范围内。

---

## Temperature 的数据流

### 数据流向图
```
Agent 表 (temperature = 0.70)
    ↓
AgentMapper.selectById(agentId)
    ↓
Agent 对象 (agent.getTemperature() = 0.70)
    ↓
ChatServiceImpl.doStreamChat()  [第 248 行]
    ↓
ChatRequest.builder()
    .temperature(agent.getTemperature())
    ↓
ProviderAdapter (OpenAI / Anthropic / 等)
    ↓
buildRequestBody() 方法
    ↓
转换为 JSON: { "temperature": 0.7 }
    ↓
发送给 LLM API
```

---

## 代码详解

### 第一步：从 Agent 读取 Temperature
文件：`hify-chat/src/main/java/com/hify/chat/service/impl/ChatServiceImpl.java`
```java
private void doStreamChat(Long sessionId, String userContent, SseEmitter emitter) throws Exception {
    // ...
    Agent agent = agentMapper.selectById(session.getAgentId());
    // 此时 agent 对象已包含 temperature 字段
}
```

### 第二步：构建 ChatRequest
文件：同上，第 243-250 行
```java
// 9. Build ChatRequest
ChatRequest chatRequest = ChatRequest.builder()
        .modelId(modelConfig.getModelId())
        .messages(messages)
        .temperature(agent.getTemperature())  // ← 传入 temperature
        .maxTokens(agent.getMaxTokens())
        .tools(toolSchemas.isEmpty() ? null : toolSchemas)
        .build();
```

### 第三步：不同的 LLM Provider 适配

#### OpenAI 适配器
文件：`hify-provider/src/main/java/com/hify/provider/adapter/impl/OpenAiAdapter.java`
```java
private String buildRequestBody(ChatRequest request, boolean stream) {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("model", request.getModelId());
    root.put("stream", stream);
    
    // 第 191-192 行：处理 temperature
    if (request.getTemperature() != null) {
        root.put("temperature", request.getTemperature().doubleValue());
    }
    
    if (request.getMaxTokens() != null) {
        root.put("max_tokens", request.getMaxTokens());
    }
    // ...
    return objectMapper.writeValueAsString(root);
}
```

**最终 JSON 格式**：
```json
{
  "model": "gpt-4",
  "temperature": 0.7,
  "max_tokens": 2048,
  "messages": [...]
}
```

#### Anthropic 适配器
文件：`hify-provider/src/main/java/com/hify/provider/adapter/impl/AnthropicAdapter.java`
```java
private String buildRequestBody(ChatRequest request, boolean stream) {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("model", request.getModelId());
    root.put("stream", stream);
    root.put("max_tokens", request.getMaxTokens() != null ? request.getMaxTokens() : 2048);
    
    // 第 159-160 行：处理 temperature
    if (request.getTemperature() != null) {
        root.put("temperature", request.getTemperature().doubleValue());
    }
    // ...
    return objectMapper.writeValueAsString(root);
}
```

**最终 JSON 格式**：
```json
{
  "model": "claude-3-opus-20240229",
  "temperature": 0.7,
  "max_tokens": 2048,
  "messages": [...]
}
```

---

## Temperature 值对应的行为

### 常见取值

| Temperature | 用途 | 特点 |
|-------------|------|------|
| **0.0** | 完全确定性推理 | 总是选最可能的词；适合翻译、代码生成、数据提取 |
| **0.3 - 0.5** | 任务型对话 | 稳定可靠，创意不足；适合客服、知识库问答 |
| **0.7**（默认） | 通用对话 | 平衡创意和稳定性；大多数场景通用 |
| **0.9 - 1.0** | 创意写作 | 高度随机，容易出现古怪回复；适合脑暴、创意写作 |

### 选择建议

**使用低温度（0.0 - 0.5）**：
- 事实性任务：根据文档回答问题
- 结构化输出：生成 JSON、SQL、代码
- 风险敏感场景：金融建议、医疗信息
- 翻译、总结

**使用中温度（0.6 - 0.8）**：
- 通用对话：日常问答、客服
- 既要准确又要有点变化的场景
- hify 默认值 0.7 适合这类场景

**使用高温度（0.8 - 1.0）**：
- 创意生成：故事、诗歌、广告文案
- 头脑风暴：想法集思广益
- 实验性应用
- 不关心准确性只要多样性

---

## hify 中 Temperature 的具体应用场景

### 1. 知识库问答 Agent
```
建议设置：temperature = 0.3 - 0.5
理由：需要准确回答用户问题，RAG 已提供具体资料，不需高创意度
```

代码位置：`ChatServiceImpl.doStreamChat()` 第 243-250 行
```java
// RAG 检索已提供了准确的资料块
List<ChunkVO> ragChunks = knowledgeService.searchChunks(...);

// 构建 ChatRequest 时使用低 temperature
ChatRequest chatRequest = ChatRequest.builder()
        .temperature(agent.getTemperature())  // 对于知识库应该用低值
        .messages(messages)  // RAG 资料已在 system prompt 中
        .build();
```

### 2. 创意写作 Agent
```
建议设置：temperature = 0.8 - 1.0
理由：需要多样化的创意输出，鼓励模型尝试新的表达方式
```

### 3. 代码生成 Agent
```
建议设置：temperature = 0.1 - 0.3
理由：代码需要高度准确，不能有随意的变化
```

---

## 在 hify UI 上的体现

当用户创建或编辑 Agent 时，可以在 UI 中设置 temperature：

```
创建 Agent 表单：
- Agent 名称：「知识库客服」
- System Prompt：「你是一个...」
- Model Config：「GPT-4」
- Temperature：【滑块：0.00 ← → 1.00】  当前值：0.7
- Max Tokens：2048
- 知识库：「产品文档 KB」
```

用户拖动滑块即可调整 temperature，实时看到标签变化（「准确」↔「创意」）。

---

## 温度参数的技术细节

### BigDecimal vs Double
代码中使用 `BigDecimal` 而不是 `double`：
```java
private BigDecimal temperature;  // Agent.java
```

**原因**：
- 数据库存储需要精确的十进制表示
- 避免浮点精度问题
- 支持数据校验（@DecimalMin, @DecimalMax）

### 转换到 JSON
当发送给 LLM API 时，转换为 `double`：
```java
if (request.getTemperature() != null) {
    root.put("temperature", request.getTemperature().doubleValue());
    // BigDecimal → double
}
```

---

## 实际效果对比

### 示例：同一个提示词，不同 Temperature

**提示词**：「用一句话描述什么是人工智能」

**Temperature = 0.1**（确定性）：
```
人工智能是指由人制造出来的机器所具备的能模仿人类智能行为的能力。
（总是返回类似的答案）
```

**Temperature = 0.5**（平衡）：
```
人工智能是指计算机系统通过学习和推理来执行任务的技术。
（略微变化，但基本思路相似）
```

**Temperature = 0.9**（创意）：
```
人工智能就是让机器学会人类的思维艺术，成为数字时代的哲学家。
（可能出现意外的、富有创意的表述）
```

---

## 总结

| 方面 | 详情 |
|------|------|
| **存储位置** | Agent 表的 `temperature` 列 |
| **数据类型** | BigDecimal，范围 0.00-1.00 |
| **默认值** | 0.70 |
| **流向** | Agent → ChatRequest → ProviderAdapter → LLM API |
| **实际作用** | 控制模型生成时的随机性和创意度 |
| **常见用途** | 低值用于准确性任务，高值用于创意任务 |
| **支持的 Provider** | OpenAI, Anthropic, Ollama 等全部支持 |

---

## 参考代码路径

1. **Agent 实体定义**：`hify-agent/src/main/java/com/hify/agent/entity/Agent.java`
2. **ChatRequest DTO**：`hify-provider/src/main/java/com/hify/provider/dto/ChatRequest.java`
3. **对话服务**：`hify-chat/src/main/java/com/hify/chat/service/impl/ChatServiceImpl.java` (第 243-250 行)
4. **OpenAI 适配**：`hify-provider/src/main/java/com/hify/provider/adapter/impl/OpenAiAdapter.java` (第 191-192 行)
5. **Anthropic 适配**：`hify-provider/src/main/java/com/hify/provider/adapter/impl/AnthropicAdapter.java` (第 159-160 行)
6. **数据库模式**：`hify-app/src/main/resources/db/schema.sql`
