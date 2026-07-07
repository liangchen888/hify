# System Prompt 详解 (Hify 实现)

## 什么是 System Prompt？

**System Prompt** 是发送给大模型的特殊指令消息，用来**定义大模型的角色、行为规范和回答方式**。

### 关键特点：
- **顺序**：在所有用户消息**之前**发送给大模型
- **优先级**：对大模型的行为有强烈的引导作用（通常优先级比用户消息更高）
- **不显示给用户**：用户看不到 System Prompt，只看到大模型的回答
- **作用**：控制大模型的人设、语言风格、回答规范、知识来源等

### 类比理解：
- **User Message** = 用户提的问题
- **System Prompt** = 给大模型的"工作说明书"

例如：
```
System Prompt: "你是一个专业的客服助手，只能基于公司知识库回答问题，不清楚的说'我不知道'"
User Message: "怎么退货？"
```

大模型会根据 System Prompt 的指示，以客服助手的身份来回答。

---

## Hify 中 System Prompt 的实现

### 1. 存储位置

**文件**: `hify-agent/src/main/java/com/hify/agent/entity/Agent.java` (第 17 行)

```java
@TableName("agent")
public class Agent extends BaseEntity {
    private String name;
    private String description;
    private String systemPrompt;  // ← System Prompt 字段
    private Long modelConfigId;
    private BigDecimal temperature;
    // ...
}
```

**含义**：
- 每个 Agent（智能体/AI助手）都有一个 `systemPrompt` 字段
- 用户创建 Agent 时，需要设置这个 Agent 的 System Prompt
- 存储在 `agent` 数据库表中

---

### 2. 如何加载 System Prompt

**文件**: `hify-chat/src/main/java/com/hify/chat/service/impl/ChatServiceImpl.java`

在执行对话前，从数据库加载 Agent 信息：

```java
// ChatServiceImpl 中读取 Agent 的 systemPrompt 字段
Agent agent = agentService.getById(agentId);
String systemPrompt = agent.getSystemPrompt();  // 读取存储的 System Prompt
```

---

### 3. System Prompt 的构建流程

#### 步骤 1: 读取原始 System Prompt
从 Agent 表获取 `systemPrompt` 字段的值

#### 步骤 2: 检查是否启用 RAG（知识库）
- **无 RAG**：直接使用 Agent 的 System Prompt
- **有 RAG**：将 RAG 检索出的资料文本追加到 System Prompt 后面

#### 步骤 3: 构建最终 System Prompt

**代码实现** (lines 528-545):

```java
private String buildSystemPrompt(String agentPrompt, List<ChunkVO> chunks) {
    if (chunks == null || chunks.isEmpty()) {
        // 没有 RAG 资料，直接返回 Agent 的原始 Prompt
        return agentPrompt;
    }
    
    StringBuilder sb = new StringBuilder();
    
    // 保留 Agent 的原始 Prompt
    if (agentPrompt != null && !agentPrompt.isBlank()) {
        sb.append(agentPrompt).append("\n\n");
    }
    
    // 追加 RAG 指示和资料
    sb.append("请基于以下参考资料回答用户问题。");
    sb.append("如果资料中没有相关信息，直接说「我没有找到相关资料」，不要编造。\n\n");
    sb.append("【参考资料】\n");
    
    // 将 RAG 检索到的每条资料都列出来
    for (int i = 0; i < chunks.size(); i++) {
        sb.append("[").append(i + 1).append("] ")
          .append(chunks.get(i).getContent()).append("\n");
    }
    
    return sb.toString();
}
```

---

### 4. System Prompt 如何被使用

#### 步骤 1: 在 `buildMessages()` 中添加到消息列表

**代码** (lines 498-525):

```java
private List<com.hify.provider.dto.ChatMessage> buildMessages(
        String systemPrompt,
        List<ChunkVO> ragChunks,
        List<Map<String, String>> contextMsgs,
        String userContent) {

    // 第一步：构建最终的 System Prompt（含 RAG 资料）
    String finalSystem = buildSystemPrompt(systemPrompt, ragChunks);

    // 第二步：创建消息列表，并将 System Prompt 作为第一条消息
    List<com.hify.provider.dto.ChatMessage> msgs = new ArrayList<>();
    
    if (finalSystem != null && !finalSystem.isBlank()) {
        com.hify.provider.dto.ChatMessage sys = new com.hify.provider.dto.ChatMessage();
        sys.setRole("system");           // ← role = "system"
        sys.setContent(finalSystem);     // ← content = 最终 System Prompt
        msgs.add(sys);                   // ← 作为第一条消息
    }
    
    // 第三步：添加历史对话上下文（从 Redis 读取的之前的对话记录）
    for (Map<String, String> ctx : contextMsgs) {
        com.hify.provider.dto.ChatMessage m = new com.hify.provider.dto.ChatMessage();
        m.setRole(ctx.get("role"));
        m.setContent(ctx.get("content"));
        msgs.add(m);
    }
    
    // 第四步：添加用户的当前问题
    com.hify.provider.dto.ChatMessage user = new com.hify.provider.dto.ChatMessage();
    user.setRole("user");
    user.setContent(userContent);
    msgs.add(user);
    
    return msgs;
}
```

#### 步骤 2: 消息列表被发送给大模型

最终发送给大模型的消息顺序：

```
[
  {
    "role": "system",
    "content": "你是一个专业的客服助手...\n\n请基于以下参考资料回答用户问题。\n【参考资料】\n[1] 退货政策：..."
  },
  {
    "role": "user",
    "content": "历史问题 1"
  },
  {
    "role": "assistant",
    "content": "之前的回答 1"
  },
  {
    "role": "user",
    "content": "历史问题 2"
  },
  {
    "role": "assistant",
    "content": "之前的回答 2"
  },
  {
    "role": "user",
    "content": "用户当前问题"
  }
]
```

**关键点**：System Prompt 总是第一条消息，作为大模型的"工作说明"。

---

## System Prompt 的具体作用

### 场景 1: 无 RAG 的 System Prompt

```
Agent System Prompt: "你是一个专业的Python编程助手，擅长解答编程问题"

最终 System Prompt = "你是一个专业的Python编程助手，擅长解答编程问题"

用户问: "如何在Python中创建字典？"

大模型回答时会：
- 以Python编程助手的身份回答
- 给出代码示例和解释
```

### 场景 2: 启用 RAG 的 System Prompt

```
Agent System Prompt: "你是公司客服助手"

RAG 检索到的资料:
- [1] 退货政策：7天无理由退货
- [2] 运费政策：满100元免运费

最终 System Prompt 变成:
"你是公司客服助手

请基于以下参考资料回答用户问题。如果资料中没有相关信息，直接说「我没有找到相关资料」，不要编造。

【参考资料】
[1] 退货政策：7天无理由退货
[2] 运费政策：满100元免运费"

用户问: "你们怎么退货？"

大模型会：
- 查看 System Prompt 中的参考资料
- 基于 [1] 的信息回答
- 如果用户问"为什么要充值?"（资料中没有），就说"我没有找到相关资料"
```

---

## System Prompt vs Temperature vs Messages

| 对比项 | System Prompt | Temperature | User Message |
|--------|--------------|------------|--------------|
| **作用** | 定义大模型角色和行为规范 | 控制回答的随机性/创意度 | 用户的实际问题 |
| **优先级** | 最高（首条消息） | 中等（影响采样过程） | 最高（需要回答） |
| **可见性** | 用户看不见 | 用户看不见 | 用户可以看到 |
| **修改频率** | 低（Agent级别，很少改） | 低（Agent级别，很少改） | 高（每次对话都不同） |
| **影响范围** | 整个对话 | 整个对话 | 当前问题 |

---

## 数据流完整图

```
1. 用户创建 Agent
   └─> 输入 System Prompt (例: "你是客服助手")
       存储到数据库 agent 表

2. 用户发起对话
   └─> 输入问题 (例: "怎么退货?")
       发送到 ChatController

3. ChatController 转发到 ChatServiceImpl
   └─> loadContext(): 从 Redis 读取历史对话
   └─> loadRag(): 从知识库检索相关资料

4. ChatServiceImpl.buildMessages()
   └─> 读取 Agent.systemPrompt: "你是客服助手"
   └─> 调用 buildSystemPrompt():
       - 无 RAG → 返回 "你是客服助手"
       - 有 RAG → 返回 "你是客服助手\n\n请基于...\n【参考资料】\n[1]..."
   └─> 构建消息列表:
       [
         { role: "system", content: "你是客服助手\n\n..." },
         { role: "user", content: "历史问题1" },
         { role: "assistant", content: "历史回答1" },
         { role: "user", content: "怎么退货?" }
       ]

5. 消息列表发送给 LLM Adapter
   └─> OpenAiAdapter / AnthropicAdapter / OllamaAdapter
   └─> 转换成该 LLM 的格式
   └─> HTTP POST 到 LLM API

6. LLM 处理
   └─> 接收 System Prompt 和历史消息
   └─> 根据 System Prompt 理解自己的角色
   └─> 根据历史消息理解对话上下文
   └─> 根据当前问题生成回答

7. LLM 返回流式数据
   └─> ChatServiceImpl 接收并保存回答
   └─> 通过 EventSource 流式返回给前端
```

---

## 与 LangChain 的对比

| 功能 | Hify | LangChain |
|------|------|----------|
| **System Prompt 存储** | 在 Agent 数据库表中 | 通常硬编码或配置文件 |
| **动态构建** | 支持（结合 RAG 动态追加资料） | 支持（通过 PromptTemplate）|
| **RAG 集成** | 在 buildSystemPrompt() 中直接拼接 | 使用 RetrievalQA / RAG 链 |
| **消息管理** | 自定义 buildMessages() 逻辑 | 使用 LangChain 的 MessageHistory |
| **LLM 适配** | 使用 Adapter Pattern（ProviderAdapter） | 使用 LangChain 的 LLM 类族 |

**核心思路相同**：都是通过 System Prompt 来指导大模型的行为，RAG 也是类似的思路。

---

## 总结

1. **System Prompt 是什么**：给大模型的"工作说明书"，定义角色和行为
2. **在 Hify 中的位置**：`Agent.systemPrompt` 数据库字段
3. **如何使用**：
   - 读取 → 可选追加 RAG 资料 → 作为第一条消息 → 发送给大模型
4. **作用范围**：影响整个对话的大模型表现
5. **与 RAG 的关系**：
   - 无 RAG：System Prompt 保持不变
   - 有 RAG：RAG 资料动态追加到 System Prompt 后面
6. **与其他参数的关系**：
   - Temperature：控制回答的创意度
   - System Prompt：控制回答的内容和角色
   - User Message：用户的实际问题

---

## 相关代码位置速查

| 功能 | 文件 | 行号 |
|------|------|------|
| System Prompt 字段定义 | `hify-agent/src/main/java/com/hify/agent/entity/Agent.java` | 第 17 行 |
| 构建消息列表 | `hify-chat/src/main/java/com/hify/chat/service/impl/ChatServiceImpl.java` | 第 498-525 行 |
| 构建最终 System Prompt | `hify-chat/src/main/java/com/hify/chat/service/impl/ChatServiceImpl.java` | 第 528-545 行 |
| 消息发送到 LLM | `hify-provider/src/main/java/com/hify/provider/dto/ChatRequest.java` | messages 字段 |
| 各 LLM Adapter | `hify-provider/src/main/java/com/hify/provider/adapter/impl/` | OpenAiAdapter 等 |
