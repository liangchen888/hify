# ChatView.vue + Fetch + ReadableStream 详解

## 1. ChatView.vue 是什么？

**ChatView.vue** 是前端聊天界面的 Vue 组件文件。

- `.vue` = Vue 单文件组件格式
- 包含三部分：
  - **`<template>`** → HTML 结构（UI 界面）
  - **`<script>`** → JavaScript 逻辑（功能实现）
  - **`<style>`** → CSS 样式（页面美化）

### 文件位置
```
hify-web/src/views/chat/ChatView.vue
```

### 功能
- 左侧：会话列表
- 右侧：聊天窗口（消息展示 + 输入框）
- 用户输入消息 → 调用后端流式 API → 实时显示 AI 回复

---

## 2. Fetch API 是什么？

**Fetch** 是浏览器原生的 HTTP 请求 API（取代了老旧的 XMLHttpRequest）。

### Fetch 基本语法
```typescript
const response = await fetch(url, {
  method: 'POST',                    // HTTP 方法
  headers: { ... },                  // 请求头
  body: JSON.stringify({ ... }),     // 请求体
  signal: controller.signal           // 取消信号
})
```

### 在你的代码中的体现
📁 `hify-web/src/api/chat.ts` → `streamMessage()` 函数

```typescript
const resp = await fetch(`/api/v1/chat/sessions/${sessionId}/messages/stream`, {
  method: 'POST',                           // ✓ POST 方法
  headers: {
    'Content-Type': 'application/json',
    Accept: 'text/event-stream',            // ✓ 期望流式响应
  },
  body: JSON.stringify({ content, stream: true }),  // ✓ 请求体
  signal: ctrl.signal,                      // ✓ 可中止
})
```

**为什么用 Fetch 而不是 EventSource？**
- ✓ Fetch 支持 POST 方法
- ✓ Fetch 支持自定义 Header
- ✓ Fetch 支持 Request Body（EventSource 不支持）

---

## 3. ReadableStream 是什么？

**ReadableStream** 是浏览器 API，用于**逐块读取**响应数据（流式处理）。

### 概念图
```
服务器发送数据流
    ↓
浏览器接收（分块）
    ↓
ReadableStream（JS API）
    ↓
getReader() 逐块读取
    ↓
处理每个 chunk（数据块）
```

### 在你的代码中的体现

📁 `hify-web/src/api/chat.ts`

```typescript
// 第1步：获取可读流对象
const reader = resp.body!.getReader()
const decoder = new TextDecoder()
let buf = ''

// 第2步：逐块读取
while (true) {
  const { done, value } = await reader.read()  // ← 读取一个 chunk
  if (done) break

  // 第3步：解码和处理
  buf += decoder.decode(value, { stream: true })
  const lines = buf.split('\n')
  buf = lines.pop() ?? ''

  // 第4步：解析 SSE 事件
  for (const line of lines) {
    if (!line.startsWith('data:')) continue
    const raw = line.slice(5).trim()
    if (!raw) continue
    try {
      const event = JSON.parse(raw)
      if (event.type === 'delta') onDelta(event.content ?? '')
      else if (event.type === 'done') onDone(event.finishReason ?? 'stop', event.latencyMs ?? 0)
      else if (event.type === 'error') onError(event.message ?? 'LLM 调用失败')
    } catch {
      // ignore malformed line
    }
  }
}
```

---

## 4. 数据流向图

```
用户在 ChatView.vue 输入消息
    ↓
点击「发送」按钮 → onSend()
    ↓
调用 streamMessage(sessionId, content, ...)
    ↓
fetch() 发送 POST 请求到后端
    ↓
后端返回流式响应（text/event-stream）
    ↓
ReadableStream 逐块接收数据
    ↓
decoder.decode() 转换为字符串
    ↓
逐行解析 SSE 事件（data: {...}）
    ↓
回调 onDelta()、onDone()、onError()
    ↓
ChatView.vue 中的回调更新 UI
    ↓
用户看到 AI 实时回复
```

---

## 5. 具体代码流程

### ChatView.vue 中的调用
```typescript
// 第1步：用户点击发送
async function onSend() {
  const content = inputText.value.trim()
  
  // 第2步：创建用户消息气泡
  const userMsg: DisplayMessage = { _tempId: uid(), role: 'user', content }
  messages.value.push(userMsg)
  
  // 第3步：创建 AI 消息气泡（loading 状态）
  const aiMsg: DisplayMessage = { _tempId: uid(), role: 'assistant', content: '', _loading: true }
  messages.value.push(aiMsg)
  
  // 第4步：调用流式 API
  streamMessage(
    sessionId,
    content,
    // onDelta 回调：每收到一个 chunk，更新 AI 消息内容
    (delta) => {
      aiMsg.content += delta          // ← 累加 AI 回复
      aiMsg._loading = false
      scrollToBottom()                // ← 自动滚到底部
    },
    // onDone 回调：流完成
    (_finishReason, _latencyMs) => {
      aiMsg._loading = false
      streaming.value = false
    },
    // onError 回调：发生错误
    (errMsg) => {
      aiMsg._error = true
      aiMsg.content = errMsg
    },
  )
}
```

### 后端响应格式（SSE）
```
data: {"type":"delta","content":"你好"}
data: {"type":"delta","content":"，我"}
data: {"type":"delta","content":"是 AI"}
data: {"type":"done","finishReason":"stop","latencyMs":1200}
```

### ReadableStream 解析过程
```
chunk 1: 'data: {"type":"delta","content":"你好"}\n'
  ↓
buffer = 'data: {"type":"delta","content":"你好"}\n'
  ↓
split('\n') = ['data: {"type":"delta","content":"你好"}', '']
  ↓
解析第一行 → JSON.parse('{"type":"delta","content":"你好"}')
  ↓
调用 onDelta('你好')

chunk 2: 'data: {"type":"delta","content":"，我"}\n'
  ↓
继续累加...
```

---

## 6. 为什么要用 ReadableStream？

### 优点
✓ **实时流式处理** — 数据一到就处理，不用等全部接收
✓ **内存高效** — 不用把整个响应加载到内存，逐块处理
✓ **用户体验好** — AI 回复边生成边显示，看起来很流畅

### 对比：如果不用 ReadableStream
```typescript
// ❌ 不好的做法：等整个响应接收完再处理
const text = await resp.text()
const lines = text.split('\n')
for (const line of lines) {
  // 处理...
}
// 问题：用户要等整个回复生成完才能看到内容！
```

---

## 7. 简单总结

| 组件 | 作用 |
|------|------|
| **ChatView.vue** | 聊天界面 + 用户交互 |
| **Fetch API** | 发送 POST 请求（支持 Body + Header） |
| **ReadableStream** | 逐块接收和处理流式响应 |
| **SSE 格式** | 服务器发送的事件流格式 |

**一句话**：用户输入 → ChatView 调用 Fetch → Fetch 获取 ReadableStream → 逐块解析 SSE → 回调更新 UI。

