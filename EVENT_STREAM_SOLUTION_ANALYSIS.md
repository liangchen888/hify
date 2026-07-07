# EventSource vs Fetch API 解决方案分析

## 问题描述

后端接口定义：
```java
@PostMapping(value = "/sessions/{sessionId}/messages/stream", produces = "text/event-stream")
public SseEmitter streamMessage(@PathVariable Long sessionId,
                                @Valid @RequestBody SendMessageRequest request) {
    return chatService.streamChat(sessionId, request);
}
```

**核心问题**：使用了 `@PostMapping + @RequestBody`，但浏览器原生 `EventSource API` 无法处理。

---

## 原因详解

### 浏览器原生 EventSource 的限制
- ✗ **仅支持 GET 请求**
- ✗ 不支持自定义 HTTP Header（除了少数例外）
- ✗ **无法携带 Request Body**
- ✗ POST 请求会返回 `405 Method Not Allowed`

### 你们的接口
- ✓ 使用了 `@PostMapping`（需要 POST 方法）
- ✓ 需要 `@RequestBody` 传参（包含 `content` 和 `stream` 字段）
- ✗ 不适合 EventSource

---

## 前端实现方案（已采用正确方案 ✓）

### 当前实现（推荐）

**文件**：`hify-web/src/api/chat.ts` → `streamMessage()` 函数

```typescript
export function streamMessage(
  sessionId: number,
  content: string,
  onDelta: (text: string) => void,
  onDone: (finishReason: string, latencyMs: number) => void,
  onError: (msg: string) => void,
): AbortController {
  const ctrl = new AbortController()

  ;(async () => {
    try {
      // 使用 fetch + POST + 自定义 Header
      const resp = await fetch(`/api/v1/chat/sessions/${sessionId}/messages/stream`, {
        method: 'POST',  // ✓ 支持 POST
        headers: {
          'Content-Type': 'application/json',
          Accept: 'text/event-stream',
        },
        body: JSON.stringify({ content, stream: true }),  // ✓ 支持 Body
        signal: ctrl.signal,
      })

      if (!resp.ok) {
        onError(`请求失败：HTTP ${resp.status}`)
        return
      }

      // 手动解析 SSE 事件流
      const reader = resp.body!.getReader()
      const decoder = new TextDecoder()
      let buf = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buf += decoder.decode(value, { stream: true })
        const lines = buf.split('\n')
        buf = lines.pop() ?? ''

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
    } catch (e: unknown) {
      if ((e as Error).name !== 'AbortError') {
        onError((e as Error).message ?? '网络异常')
      }
    }
  })()

  return ctrl
}
```

**使用方式**（`ChatView.vue`）：
```typescript
streamMessage(
  sessionId,
  content,
  (delta) => {
    aiMsg.content += delta
    aiMsg._loading = false
    scrollToBottom()
  },
  (_finishReason, _latencyMs) => {
    aiMsg._loading = false
    streaming.value = false
    sessionPreviewMap.value[sessionId] = truncate(aiMsg.content, 30)
  },
  (errMsg) => {
    aiMsg._loading = false
    aiMsg._error = true
    aiMsg.content = errMsg || 'LLM 调用失败'
    streaming.value = false
  },
)
```

---

## 方案对比

| 特性 | EventSource | Fetch + ReadableStream | axios |
|------|------------|----------------------|-------|
| HTTP 方法 | 仅 GET | GET/POST/PUT/DELETE 等 | GET/POST/PUT/DELETE 等 |
| 自定义 Header | 否（限制少数） | ✓ 是 | ✓ 是 |
| Request Body | ✗ 否 | ✓ 是 | ✓ 是 |
| 浏览器兼容性 | 广泛 | 广泛 | 需依赖库 |
| 实现难度 | 简单 | 中等 | 简单（依赖 axios） |
| **适用场景** | 简单 GET SSE | **POST 流式 API** | **POST 流式 API** |

---

## 总结

### ✓ 你们已正确实现

1. **后端方案** → 使用 `@PostMapping + produces = "text/event-stream"` ✓
2. **前端方案** → 使用 `fetch API + ReadableStream` 手动处理 SSE ✓
3. **支持的功能** → POST + 自定义 Header + Request Body 都支持 ✓

### 关键代码位置

| 组件 | 文件 | 说明 |
|------|------|------|
| 后端 API | `hify-chat/src/main/java/com/hify/chat/controller/ChatController.java` | `@PostMapping(.../stream)` |
| 前端 API | `hify-web/src/api/chat.ts` | `streamMessage()` 函数 |
| 前端 UI | `hify-web/src/views/chat/ChatView.vue` | `onSend()` 调用流 |

### 如果要改成 EventSource（不推荐）

若非要使用 `EventSource`，只能改成：

```java
@GetMapping(value = "/sessions/{sessionId}/messages/stream", produces = "text/event-stream")
public SseEmitter streamMessage(@PathVariable Long sessionId,
                                @RequestParam String content) {  // 参数放 URL
    return chatService.streamChat(sessionId, content);
}
```

但这样做的**缺点**：
- ✗ 长查询字符串不安全（密钥、隐私内容）
- ✗ URL 长度限制（浏览器/服务器）
- ✗ 浏览器历史记录会记录 URL

---

## 结论

**现有实现是最优解**。无需修改。✓

