# 同步对话 vs 流式对话 判断指南

## 核心区别

| 特性 | 流式对话 | 同步对话 |
|------|--------|--------|
| **API 端点** | `/messages/stream` | `/messages` |
| **HTTP 方法** | POST | POST |
| **返回方式** | 逐块发送（SSE 流） | 一次性返回完整结果 |
| **响应时间** | 快速开始，慢速完成 | 全部等待，一次返回 |
| **用户体验** | 看到 AI 实时打字 | 等待转圈，然后突然出现完整回复 |
| **内存占用** | 低（逐块处理） | 高（全部加载） |
| **适用场景** | 长文本、代码、需要等待 | 短回复、快速查询 |

---

## 怎么判断该用哪个？

### ✅ 用流式对话（/messages/stream）

**适用情况：**

1. **AI 回复很长**
   - 📝 整段文章、代码块、详细解释
   - 用户希望看到 AI 边生成边展示

2. **需要实时反馈**
   - ⏳ 用户不想等待，希望立即看到动作
   - 比如 ChatGPT 网页版的实时打字效果

3. **响应时间不确定**
   - 🕐 后端处理时间长（调用 LLM、搜索、计算）
   - 用户看到动画，心理上觉得"没卡死"

4. **当前代码现状**
   - 📁 `ChatView.vue` 中 `onSend()` 调用的就是 `streamMessage()`
   - 这是 **默认方案**

**代码示例：**
```typescript
// ChatView.vue 中
streamMessage(
  sessionId,
  content,
  (delta) => {
    aiMsg.content += delta  // 实时累加
    scrollToBottom()
  },
  (finishReason, latencyMs) => {
    streaming.value = false
  }
)
```

---

### ❌ 用同步对话（/messages）

**适用情况：**

1. **回复很短**
   - 💬 一句话、是/否、单数字
   - 例如：搜索结果数量、状态查询

2. **不需要实时感**
   - 🎬 后端极快返回（< 100ms）
   - 用户感受不到流式的好处

3. **特殊业务需求**
   - 📊 需要完整数据做统计、展示表格
   - 需要在显示前做完整验证

4. **移动端考虑**
   - 📱 网络不稳定，流式可能中断
   - 同步一次性返回更稳定

**代码示例：**
```typescript
// 如果要调用同步对话（需要修改 ChatView.vue）
const result = await sendMessage(sessionId, content)
// 一次性获得完整回复
aiMsg.content = result.content
```

---

## 当前代码分析

### 后端现状

```java
// 流式接口
@PostMapping(value = "/sessions/{sessionId}/messages/stream", produces = "text/event-stream")
public SseEmitter streamMessage(@PathVariable Long sessionId,
                                @Valid @RequestBody SendMessageRequest request) {
    return chatService.streamChat(sessionId, request);
}

// 同步接口
@PostMapping("/sessions/{sessionId}/messages")
public Result<MessageResp> sendMessage(@PathVariable Long sessionId,
                                       @Valid @RequestBody SendMessageRequest request) {
    return Result.ok(chatService.syncChat(sessionId, request));
}
```

**特点：**
- 两个接口都存在
- 后端不做判断，由**前端选择调用哪个**

---

### 前端现状

```typescript
// ChatView.vue - onSend() 函数
async function onSend() {
  // ...
  streamMessage(          // ← 总是调用流式接口
    sessionId,
    content,
    (delta) => { ... },   // 实时处理
    (finishReason, latencyMs) => { ... },
    (errMsg) => { ... },
  )
}
```

**特点：**
- **硬编码**，总是使用流式对话
- 没有判断逻辑

---

## 建议方案

### 方案 1：前端判断（推荐）

在 `ChatView.vue` 中添加判断逻辑：

```typescript
async function onSend() {
  const content = inputText.value.trim()
  if (!content) return

  // 判断：是否应该用流式对话
  const shouldStream = shouldUseStream(content)

  if (shouldStream) {
    // 调用流式 API
    streamMessage(sessionId, content, onDelta, onDone, onError)
  } else {
    // 调用同步 API（需要实现）
    const result = await sendMessage(sessionId, { content, stream: false })
    aiMsg.content = result.content
  }
}

// 判断逻辑
function shouldUseStream(content: string): boolean {
  // 规则 1：内容长度
  if (content.includes('代码') || content.includes('解释')) {
    return true  // 可能回复很长，用流式
  }

  // 规则 2：关键词
  if (content.match(/^(是|否|数量|个数|多少)/)) {
    return false  // 简短回复，用同步
  }

  // 规则 3：默认用流式（更好的用户体验）
  return true
}
```

---

### 方案 2：后端判断

在后端增加一个统一入口：

```java
@PostMapping("/sessions/{sessionId}/messages")
public Object sendMessage(@PathVariable Long sessionId,
                          @Valid @RequestBody SendMessageRequest request) {
    // 后端判断
    if (shouldStream(request)) {
        // 返回 SSE 流
        return new SseEmitter();  // streamChat()
    } else {
        // 返回同步结果
        return Result.ok(syncChat());
    }
}

private boolean shouldStream(SendMessageRequest request) {
    // 根据消息长度、Agent 类型等判断
    return request.getContent().length() > 50;
}
```

---

### 方案 3：用户选择（最灵活）

在 UI 上加一个切换按钮：

```typescript
const useStreamMode = ref(true)  // 用户设置

// 在发送前检查用户选择
if (useStreamMode.value) {
    streamMessage(...)
} else {
    await sendMessage(...)
}
```

对应的 UI：
```vue
<el-switch v-model="useStreamMode" />
实时显示模式
```

---

## 快速判断流程图

```
用户输入消息
    ↓
判断内容属性
    ↓
    ├─ 长文本/代码/解释 → 用流式 ✓ streamMessage()
    ├─ 短回复/查询/数据 → 用同步 ✓ sendMessage()
    └─ 默认 → 用流式（更好体验）
    ↓
调用对应 API
```

---

## 总结

| 判断维度 | 用流式 | 用同步 |
|---------|-------|-------|
| 回复长度 | 长 | 短 |
| 等待时间 | 不想等 | 快速返回 |
| 用户体验 | 看过程 | 看结果 |
| 网络状态 | 稳定 | 不稳定 |
| 默认推荐 | ✅ 优先选 | ⚠️ 特殊场景 |

---

## 你的项目现状

**现在：** 总是使用流式对话 ✓
- 优点：用户体验好，显得"不卡"
- 缺点：没有考虑短回复的情况

**建议：** 保持现状（如果 AI 主要生成长文本的话）

