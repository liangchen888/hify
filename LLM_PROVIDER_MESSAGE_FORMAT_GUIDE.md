# LLM 提供商消息格式对比指南

## 概述

在 Hify 架构中，**每个大模型提供商接受的消息格式是不同的**。为了解决这个问题，Hify 使用了**适配器模式**：

1. **统一内部格式** (`ChatRequest` + `ChatMessage`)：所有业务逻辑都使用统一的格式
2. **提供商适配器** (`ProviderAdapter`)：每个适配器负责将统一格式转换成该提供商的具体 API 格式
3. **消息转换** (`buildRequestBody()`)：在调用提供商 API 前，将通用格式转换成提供商特定格式

```
业务逻辑
    ↓
ChatRequest + ChatMessage (统一格式)
    ↓
ProviderAdapter.buildRequestBody() (转换)
    ↓
提供商特定 API 格式
    ↓
HTTP POST 到提供商
```

---

## 统一内部格式

### ChatRequest (请求包装)

```java
{
  "modelId": "gpt-4",
  "messages": [ChatMessage, ChatMessage, ...],
  "temperature": 0.7,
  "maxTokens": 2048,
  "stream": true,
  "tools": [...]  // Function Calling 工具列表
}
```

### ChatMessage (单个消息)

```java
{
  "role": "user|assistant|system|tool",
  "content": "消息文本",
  "toolCalls": [...],      // assistant 发起工具调用时有值
  "toolCallId": "..."      // role=tool 时必填，关联 assistant 的 tool_call id
}
```

---

## 各提供商的具体格式

### 1. OpenAI 格式 (`OpenAiAdapter`)

#### 特点
- **System 消息在数组内**（与消息并列）
- **支持 Function Calling**，有 `tools` 字段
- **Tool 角色包含 `tool_call_id`**
- 流式格式：每行 `data: {...JSON...}` 前缀

#### 转换逻辑
```java
// OpenAiAdapter.buildRequestBody(ChatRequest request, boolean stream)

{
  "model": "gpt-4",
  "stream": true,
  "temperature": 0.7,
  "max_tokens": 2048,
  "tools": [{...}],  // 如果 request.tools 不为空
  "messages": [
    {
      "role": "system",
      "content": "你是一个助手"
    },
    {
      "role": "user",
      "content": "你好"
    },
    {
      "role": "assistant",
      "content": null,
      "tool_calls": [
        {
          "id": "call_xxx",
          "type": "function",
          "function": {
            "name": "get_weather",
            "arguments": "{\"city\": \"北京\"}"
          }
        }
      ]
    },
    {
      "role": "tool",
      "tool_call_id": "call_xxx",
      "content": "北京天气晴朗"
    }
  ]
}
```

#### 关键代码位置
- `buildRequestBody()` 第 130-180 行
- System 消息直接加入 messages 数组：`node.put("role", "system")`
- Tool 角色处理：`node.put("tool_call_id", m.getToolCallId())`

---

### 2. Anthropic 格式 (`AnthropicAdapter`)

#### 特点
- **System 消息在顶层** `"system": "..."` 字段，**不在 messages 数组里**
- **`max_tokens` 是必填字段**（OpenAI 是可选）
- **不支持 Function Calling**
- 流式格式：每行 `data: {...JSON...}` 前缀，但事件类型不同

#### 转换逻辑
```java
// AnthropicAdapter.buildRequestBody(ChatRequest request, boolean stream)

{
  "model": "claude-3-sonnet-20240229",
  "stream": true,
  "max_tokens": 2048,  // ⭐ 必填！OpenAI 是可选的
  "temperature": 0.7,
  "system": "你是一个助手",  // ⭐ 顶层字段，不在 messages 里
  "messages": [
    {
      "role": "user",
      "content": "你好"
    },
    {
      "role": "assistant",
      "content": "你好！有什么我可以帮助的吗？"
    }
  ]
}
```

#### 关键代码位置
- `buildRequestBody()` 第 110-150 行
- **System 消息提取到顶层**：
  ```java
  for (ChatMessage m : request.getMessages()) {
    if ("system".equals(m.getRole())) {
      root.put("system", m.getContent());  // ⭐ 不加入 messages
      continue;
    }
    // 其他消息加入数组
  }
  ```
- **max_tokens 强制设置**：`root.put("max_tokens", 2048)`

#### 流式事件类型
- `content_block_delta`：文本增量，从 `delta.text` 取值
- `message_delta`：消息结束信号，含 `stop_reason`
- `message_stop`：完整消息停止

---

### 3. Ollama 格式 (`OllamaAdapter`)

#### 特点
- **兼容 OpenAI 格式**（使用 `/v1/chat/completions` 端点）
- **无需认证**（无 Authorization 头）
- **流式格式不同**：每行是完整 JSON（**无 `data: ` 前缀**）
- 直接复用 `OpenAiAdapter.buildRequestBody()`

#### 请求格式
```javascript
// 与 OpenAI 完全相同
{
  "model": "llama3",
  "stream": true,
  "messages": [...]
}
```

#### 流式格式（与 OpenAI 不同）
```javascript
// ❌ 不是 OpenAI 的 "data: " 格式
{"model":"llama3","message":{"role":"assistant","content":"你"},"done":false}
{"model":"llama3","message":{"role":"assistant","content":"好"},"done":false}
{"model":"llama3","done":true,"done_reason":"stop"}

// ✅ 直接是 JSON，无前缀
```

#### 关键代码位置
- 无需覆盖 `buildRequestBody()`，直接调用 `openAiAdapter.buildRequestBody()`
- 流式处理 `streamChat()` 中每行是完整 JSON：
  ```java
  llmHttpClient.stream(..., line -> {
    JsonNode root = objectMapper.readTree(line);  // ⭐ 直接解析，无前缀
    String delta = root.path("message").path("content").asText();
  });
  ```

---

### 4. Azure OpenAI 格式 (`AzureOpenAiAdapter`)

#### 特点
- **消息格式与 OpenAI 完全相同**
- **URL 结构不同**：路径包含 deployment 名称和 api-version
- **认证头不同**：用 `api-key` 而非 `Authorization: Bearer`

#### 请求格式
```javascript
// 与 OpenAI 完全相同
{
  "model": "gpt-4",
  "stream": true,
  "messages": [...]
}
```

#### URL 模板
```
{baseUrl}/openai/deployments/{deploymentName}/chat/completions?api-version={apiVersion}
```

例如：
```
https://my-azure-openai.openai.azure.com/openai/deployments/gpt4-deployment/chat/completions?api-version=2024-02-01
```

#### 认证
```java
// OpenAI
Authorization: Bearer sk-...

// Azure OpenAI
api-key: <api-key-value>
```

#### 关键代码位置
- 覆盖 `chatUrl()` 和 `modelsUrl()` 拼接路径
- 覆盖 `authHeaders()` 返回 `api-key` 头
- `chat()` 和 `streamChat()` 100% 复用父类

---

### 5. OpenAI 兼容格式 (`OpenAiCompatibleAdapter`)

#### 特点
- **与 OpenAI 接口完全兼容**
- 支持的提供商：
  - DeepSeek
  - Moonshot
  - 其他 OpenAI-compatible 服务

#### 代码
```java
@Component
public class OpenAiCompatibleAdapter extends OpenAiAdapter {
    @Override
    public List<String> supportedTypes() {
        return List.of("OPENAI_COMPATIBLE", "DEEPSEEK");
    }
}
```

完全继承 `OpenAiAdapter`，无需覆盖任何方法。

---

## 消息流动示例

### 场景：用户发送一条消息

**步骤 1：ChatService 构建统一格式**
```java
ChatRequest request = ChatRequest.builder()
    .modelId("gpt-4")
    .messages(List.of(
        ChatMessage.system("你是一个助手"),
        ChatMessage.user("你好")
    ))
    .temperature(BigDecimal.valueOf(0.7))
    .maxTokens(2048)
    .stream(true)
    .build();
```

**步骤 2：根据 Provider 类型选择 Adapter**
```java
ProviderAdapter adapter = ProviderAdapterFactory.getAdapter(provider);
// 如果 provider.type = "OPENAI"  → OpenAiAdapter
// 如果 provider.type = "ANTHROPIC" → AnthropicAdapter
// 如果 provider.type = "OLLAMA"   → OllamaAdapter
```

**步骤 3：Adapter 转换格式**

**如果是 OpenAI**：
```javascript
{
  "model": "gpt-4",
  "stream": true,
  "temperature": 0.7,
  "max_tokens": 2048,
  "messages": [
    {"role": "system", "content": "你是一个助手"},
    {"role": "user", "content": "你好"}
  ]
}
```

**如果是 Anthropic**：
```javascript
{
  "model": "claude-3-sonnet",
  "stream": true,
  "max_tokens": 2048,
  "temperature": 0.7,
  "system": "你是一个助手",  // ⭐ 提取到顶层
  "messages": [
    {"role": "user", "content": "你好"}
  ]
}
```

**步骤 4：HTTP POST**
```
OpenAI:     POST https://api.openai.com/v1/chat/completions
Anthropic:  POST https://api.anthropic.com/v1/messages
Ollama:     POST http://localhost:11434/v1/chat/completions
```

**步骤 5：Adapter 解析流式响应**
```
OpenAI:    data: {"choices":[{"delta":{"content":"你"}}]}
Anthropic: data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"你"}}
Ollama:    {"message":{"content":"你"},"done":false}
```

---

## 对比表

| 特性 | OpenAI | Anthropic | Ollama | Azure OpenAI | OpenAI Compatible |
|------|--------|-----------|--------|--------------|-------------------|
| System 消息位置 | messages 数组 | **顶层字段** | messages 数组 | messages 数组 | messages 数组 |
| max_tokens 必填 | ❌ 可选 | ✅ **必填** | ❌ 可选 | ❌ 可选 | ❌ 可选 |
| Function Calling | ✅ 支持 | ❌ 不支持 | ❌ 不支持 | ✅ 支持 | ✅ 支持 |
| Tool 角色 | ✅ 有 | ❌ 无 | ❌ 无 | ✅ 有 | ✅ 有 |
| 流式前缀 | `data: ` | `data: ` | **无** | `data: ` | `data: ` |
| 认证方式 | `Authorization: Bearer` | `x-api-key` | 无 | **`api-key`** | `Authorization: Bearer` |
| URL 模板 | `/v1/chat/completions` | `/v1/messages` | `/v1/chat/completions` | `/openai/deployments/{name}/...` | `/v1/chat/completions` |

---

## 对 Query 6 的完整答案

**问题**：新的 message 是否会把新消息和之前 Redis 中上下文给大模型？

**答案**：**是的**。流程如下：

1. **加载上下文** → `loadContext()` 从 Redis 读取最近 N 条消息（滑动窗口）
2. **构建消息** → `buildMessages()` 组合：系统 prompt + RAG 块 + **Redis 中的历史消息** + 当前用户输入
3. **统一格式** → 组装成 `ChatRequest.messages` 数组
4. **提供商转换** → 对应 Adapter 转换成该提供商的格式
5. **发送给 LLM** → LLM 看到完整的消息历史（在滑动窗口范围内）

关键代码：
```java
// ChatServiceImpl.buildMessages() 第 498-525 行
// 组合顺序：系统 prompt → RAG chunks → 历史消息(来自 Redis) → 新消息
messages.add(ChatMessage.system(systemPrompt));
// ... RAG blocks ...
messages.addAll(context);  // ⭐ Redis 中的历史消息
messages.add(ChatMessage.user(userInput));  // ⭐ 新消息
```

所以 **LLM 在每次调用时都能看到完整的对话历史**。

---

## 如何添加新的 LLM 提供商

如果要集成新的 LLM 提供商（如 Groq、Together 等），步骤是：

1. **创建新的 Adapter 类**
   ```java
   @Component
   public class GroqAdapter extends OpenAiAdapter {
       @Override
       public List<String> supportedTypes() {
           return List.of("GROQ");
       }
       
       // 覆盖必要的方法...
   }
   ```

2. **判断消息格式是否与现有提供商兼容**
   - 如果与 OpenAI 兼容 → 直接继承 `OpenAiAdapter`，只需覆盖 URL 和认证
   - 如果与 Anthropic 相似 → 继承 `AnthropicAdapter`
   - 如果格式完全不同 → 实现 `ProviderAdapter` 接口，编写完整的格式转换逻辑

3. **重点关注**
   - `buildRequestBody()` 的 messages 数组构造
   - System 消息是否需要提取到顶层
   - 是否有额外的必填字段（如 `max_tokens`）
   - 认证方式（Bearer token、API key、自定义 header）
   - 流式响应格式（`data: ` 前缀？JSON 格式？）

4. **在 `ProviderAdapterFactory` 中注册**
   ```java
   // ProviderAdapterFactory.getAdapter(Provider)
   case "GROQ":
       return groqAdapter;
   ```

---

## 关键文件位置

| 功能 | 文件 |
|------|------|
| 统一请求格式 | `hify-provider/dto/ChatRequest.java` |
| 统一消息格式 | `hify-provider/dto/ChatMessage.java` |
| Adapter 接口 | `hify-provider/adapter/ProviderAdapter.java` |
| Adapter 工厂 | `hify-provider/adapter/ProviderAdapterFactory.java` |
| OpenAI 格式 | `hify-provider/adapter/impl/OpenAiAdapter.java` |
| Anthropic 格式 | `hify-provider/adapter/impl/AnthropicAdapter.java` |
| Ollama 格式 | `hify-provider/adapter/impl/OllamaAdapter.java` |
| Azure 格式 | `hify-provider/adapter/impl/AzureOpenAiAdapter.java` |
| 消息构建 | `hify-chat/service/impl/ChatServiceImpl.java` (buildMessages 第 498-525 行) |
| 上下文加载 | `hify-chat/service/impl/ChatServiceImpl.java` (loadContext 第 462-474 行) |

---

## 总结

在 Hify 中，**不同的大模型接受不同的消息格式**。解决方案是：

✅ **内部统一** → 所有业务逻辑使用 `ChatRequest` + `ChatMessage`  
✅ **外部转换** → 每个 Adapter 负责转换成提供商特定格式  
✅ **易于扩展** → 添加新提供商只需实现 Adapter  

这样既保持了代码的整洁性，又支持了多个 LLM 提供商的灵活集成。
