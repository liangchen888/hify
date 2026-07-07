# hify LLM 通用参数分析

**问题**：除了 temperature，还有哪些是大模型都有的参数？

**答案**：根据 ChatRequest 定义和 6 个 Adapter 实现的分析，hify 支持的参数分为三类：
- **通用必需参数**：所有大模型都需要的参数
- **通用可选参数**：大多数大模型都支持的参数
- **专有/特定参数**：某些大模型特有的参数

---

## 1. ChatRequest 字段清单

`hify-provider/src/main/java/com/hify/provider/dto/ChatRequest.java` 定义的所有参数：

| 字段名 | 类型 | 说明 |
|--------|------|------|
| `modelId` | String | 模型标识（必需）|
| `messages` | List<ChatMessage> | 对话消息列表（必需）|
| `temperature` | BigDecimal | 生成随机性，0.00~1.00（可选）|
| `maxTokens` | Integer | 最大输出 token 数（可选）|
| `stream` | boolean | 是否流式响应（可选，默认 false）|
| `tools` | List<Map<String, Object>> | Function Calling 工具列表（可选）|

---

## 2. 各 Adapter 参数使用对比表

### OpenAI (OpenAiAdapter)
**代码位置**：`hify-provider/src/main/java/com/hify/provider/adapter/impl/OpenAiAdapter.java` (lines 186-230)

**使用的参数**：
```json
{
  "model": "modelId",           // ✅ 必需
  "stream": false,              // ✅ 必需  
  "temperature": 0.7,           // ⚠️  可选（if != null）
  "max_tokens": 2000,           // ⚠️  可选（if != null）
  "tools": [...],               // ⚠️  可选（if != null && !empty）
  "messages": [...]             // ✅ 必需
}
```

**关键特点**：
- `temperature` 存在时才写入（line 191-192）
- `maxTokens` 存在时才写入（line 193-195）
- `tools` 不为空时启用 Function Calling（line 197-199）
- tool 角色消息需要 `tool_call_id` 字段

---

### Anthropic (AnthropicAdapter)
**代码位置**：`hify-provider/src/main/java/com/hify/provider/adapter/impl/AnthropicAdapter.java` (lines 153-179)

**使用的参数**：
```json
{
  "model": "modelId",                    // ✅ 必需
  "stream": false,                       // ✅ 必需
  "max_tokens": 2048,                    // ✅ 必需（无default时用2048）
  "temperature": 0.7,                    // ⚠️  可选（if != null）
  "system": "system message content",    // ⚠️  特殊处理：单独提取
  "messages": [...]                      // ✅ 必需
}
```

**关键特点**：
- `max_tokens` **必需字段**，无默认值时设为 2048（line 157）
- `temperature` 可选（line 158-160）
- **system 消息单独处理**（line 163）：不放在 messages 数组，而是提取到顶层 `system` 字段
- 不支持 Function Calling

---

### Azure OpenAI (AzureOpenAiAdapter)
**代码位置**：`hify-provider/src/main/java/com/hify/provider/adapter/impl/AzureOpenAiAdapter.java`

**继承**：直接继承 `OpenAiAdapter`，完全复用父类的 `buildRequestBody` 方法

**特殊点**：
- URL 格式不同：`/openai/deployments/{deploymentName}/chat/completions?api-version={apiVersion}`
- 认证方式不同：使用 API Key（`api-key` header）而非 Bearer Token
- **参数格式与 OpenAI 完全相同**

---

### Ollama (OllamaAdapter)
**代码位置**：`hify-provider/src/main/java/com/hify/provider/adapter/impl/OllamaAdapter.java`

**复用关系**：
```java
// 行 60-74：stream chat 时复用 OpenAI 的 buildRequestBody
String body = openAiAdapter.buildRequestBody(request, true);
```

**特殊点**：
- 完全兼容 OpenAI `/v1/chat/completions` 格式
- **参数格式与 OpenAI 完全相同**
- 流式响应格式略有不同：每行一个完整 JSON，无 `"data: "` 前缀

---

### OpenAI Compatible / DeepSeek / Moonshot (OpenAiCompatibleAdapter)
**代码位置**：`hify-provider/src/main/java/com/hify/provider/adapter/impl/OpenAiCompatibleAdapter.java`

**继承**：直接继承 `OpenAiAdapter`

**特殊点**：
- 支持类型：`OPENAI_COMPATIBLE`, `DEEPSEEK`
- **参数格式与 OpenAI 完全相同**
- 兼容所有 OpenAI 格式的供应商（DeepSeek、Moonshot 等）

---

### Mock Provider (MockProviderAdapter)
**代码位置**：`hify-provider/src/main/java/com/hify/provider/adapter/impl/MockProviderAdapter.java`

**特殊点**：
- 仅测试环境使用（`@Profile("mock")`）
- **不实际使用任何参数**，直接返回 mock 数据
- 支持模拟 Function Calling

---

## 3. 参数分类总结

### 📌 通用必需参数（所有大模型都需要）
| 参数 | OpenAI | Anthropic | Ollama | Azure | Compatible | 
|------|--------|-----------|--------|-------|------------|
| `modelId` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `messages` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `stream` | ✅ | ✅ | ✅ | ✅ | ✅ |

**说明**：这三个参数在所有实现中都是必需的，无null检查。

---

### ⚠️ 通用可选参数（大多数大模型支持）
| 参数 | OpenAI | Anthropic | Ollama | Azure | Compatible | 
|------|--------|-----------|--------|-------|------------|
| `temperature` | 有null检查 | 有null检查 | ✅ 继承OpenAI | ✅ 继承OpenAI | ✅ 继承OpenAI |
| `maxTokens` | 有null检查 | **必需** | ✅ 继承OpenAI | ✅ 继承OpenAI | ✅ 继承OpenAI |

**说明**：
- `temperature`：所有大模型都支持，值范围 0.0-1.0
  - 0.0 = 确定性（deterministic），低随机性
  - 1.0 = 最高创意性（creative），高随机性
  - hify 默认值：0.70
  - **标准 LLM API 参数**，不是 hify 自定义

- `maxTokens`：所有大模型都支持限制输出长度
  - OpenAI / Ollama / Azure / Compatible：有null检查（可选）
  - Anthropic：**必需字段**，hify 无值时默认 2048
  - 单位：token（不同模型计算方式略有差异）

---

### 🔧 专有/特定参数
| 参数 | 支持 | 说明 |
|------|------|------|
| `tools` | OpenAI 系列 | Function Calling（工具调用）。仅 OpenAI、Azure、Compatible 支持。Anthropic/Ollama 不支持。|
| `system` 字段 | Anthropic | 系统消息单独提取到顶层 `system` 字段，不放在 messages 数组。|
| tool_call_id | OpenAI 系列 | tool 角色消息需要 `tool_call_id` 字段标识来自哪个工具调用。|

---

## 4. ChatRequest → 各 Adapter 的参数映射

### 通过 ChatServiceImpl 构建 ChatRequest

**代码位置**：`hify-chat/src/main/java/com/hify/chat/service/impl/ChatServiceImpl.java` (lines 243-250)

```java
ChatRequest chatRequest = ChatRequest.builder()
        .modelId(modelConfig.getModelId())              // 模型 ID
        .messages(messages)                              // 消息列表
        .temperature(agent.getTemperature())            // 从 Agent entity 读取（default 0.70）
        .maxTokens(agent.getMaxTokens())                // 从 Agent entity 读取
        .tools(toolSchemas.isEmpty() ? null : toolSchemas)  // 工具列表
        .build();
```

**数据流**：
```
Agent.temperature (DB)
     ↓
ChatRequest.temperature
     ↓
Adapter.buildRequestBody()
     ↓
LLM API JSON body
```

---

## 5. 标准 LLM 参数 vs hify 自定义字段

### 标准 LLM API 参数（所有主流大模型官方支持）
- ✅ `modelId` / `model`
- ✅ `messages`
- ✅ `temperature`
- ✅ `maxTokens` / `max_tokens`
- ✅ `stream`
- ✅ `tools`（OpenAI Function Calling 标准）

**来源**：
- OpenAI API 官方文档：https://platform.openai.com/docs/api-reference/chat/create
- Anthropic API 官方文档：https://docs.anthropic.com/en/api/messages
- Ollama API：兼容 OpenAI `/v1/chat/completions`

### hify 自定义字段（在 Agent entity 中，但最终转换为标准参数）
- ❌ `Agent.temperature`：**不是自定义字段**，只是存储位置
- ❌ `Agent.maxTokens`：**不是自定义字段**，只是存储位置

**说明**：hify 没有创新性的自定义参数。所有参数都是标准 LLM API 参数，hify 只是：
1. 在 Agent entity 中持久化这些参数
2. 在调用时从 Agent 读取
3. 通过 Adapter 按照每个 LLM 的格式转换并发送

---

## 6. 参数转换示例

### 用户创建 Agent 并配置参数

```
Agent {
  modelId: "gpt-4",
  temperature: 0.7,
  maxTokens: 2000,
  tools: [...]
}
```

### OpenAI Adapter 转换
```json
{
  "model": "gpt-4",
  "stream": false,
  "temperature": 0.7,
  "max_tokens": 2000,
  "tools": [...],
  "messages": [...]
}
```

### Anthropic Adapter 转换
```json
{
  "model": "gpt-4",
  "stream": false,
  "temperature": 0.7,
  "max_tokens": 2000,
  "messages": [...]
  // 注意：不支持 tools，会被忽略
  // 注意：system 消息单独处理
}
```

### Ollama Adapter 转换
```json
{
  "model": "gpt-4",
  "stream": false,
  "temperature": 0.7,
  "max_tokens": 2000,
  "messages": [...]
  // 注意：不支持 tools，会被忽略
}
```

---

## 7. 总结表

| 维度 | 描述 |
|------|------|
| **通用必需** | `modelId`, `messages`, `stream` |
| **通用可选** | `temperature`, `maxTokens` |
| **OpenAI 专有** | `tools`（Function Calling）|
| **Anthropic 特殊** | `system` 字段单独处理，`maxTokens` 必需 |
| **自定义字段** | ❌ 无。所有参数都是标准 LLM API 参数 |
| **标准来源** | OpenAI API, Anthropic API, Ollama (兼容 OpenAI) |

---

## 8. 代码定位总结

- **参数定义**：`ChatRequest.java` (6 个字段)
- **参数存储**：`Agent.java` (temperature, maxTokens 持久化)
- **参数构建**：`ChatServiceImpl.java` (line 243-250)
- **参数转换**：
  - OpenAI: `OpenAiAdapter.java` (line 186-230)
  - Anthropic: `AnthropicAdapter.java` (line 153-179)
  - Ollama: `OllamaAdapter.java` (复用 OpenAiAdapter)
  - Azure: `AzureOpenAiAdapter.java` (继承 OpenAiAdapter)
  - Compatible: `OpenAiCompatibleAdapter.java` (继承 OpenAiAdapter)

---

## 9. 参考资料

- OpenAI Chat Completions API: https://platform.openai.com/docs/api-reference/chat/create
- Anthropic Messages API: https://docs.anthropic.com/en/api/messages
- Ollama API: https://github.com/ollama/ollama/blob/main/docs/api.md
- Azure OpenAI: https://learn.microsoft.com/en-us/azure/ai-services/openai/reference
