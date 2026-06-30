package com.hify.mcp.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.*;

/**
 * 支持两种 MCP 协议：
 * 1. Streamable HTTP：POST endpoint，响应 text/event-stream（如 mcp.mcd.ai）
 * 2. SSE：GET /sse 建立连接获取 sessionId，POST /mcp?sessionId=xxx 发消息（如本地 Spring AI）
 */
@Slf4j
public class DirectMcpClient implements AutoCloseable {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int CONNECT_TIMEOUT_SEC = 10;
    private static final int READ_TIMEOUT_SEC = 30;
    private static final int SSE_CONNECT_TIMEOUT_SEC = 5;

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    /** 原始 endpoint（数据库存的值）*/
    private final String rawEndpoint;

    /** 协议类型 */
    private enum Protocol { STREAMABLE_HTTP, SSE }
    private Protocol protocol;

    /** Streamable HTTP 模式：直接 POST 的 URL */
    private String streamableUrl;

    /** SSE 模式：建立连接的 URL */
    private String sseUrl;
    /** SSE 模式：发消息的 URL（不含 sessionId） */
    private String messageUrl;
    /** SSE 模式：握手后拿到的 sessionId */
    private String sessionId;

    public DirectMcpClient(String endpoint) {
        this.rawEndpoint = endpoint.endsWith("/")
                ? endpoint.substring(0, endpoint.length() - 1)
                : endpoint;
        this.objectMapper = new ObjectMapper();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
                .build();
        detectProtocol();
    }

    /**
     * 根据 endpoint 判断协议类型：
     * - 以 /sse 结尾 → SSE 协议
     * - 其他 → Streamable HTTP
     */
    private void detectProtocol() {
        if (rawEndpoint.endsWith("/sse")) {
            protocol = Protocol.SSE;
            sseUrl = rawEndpoint;
            // 消息端点：把 /sse 替换为 /mcp（从 application.yml 得知）
            messageUrl = rawEndpoint.substring(0, rawEndpoint.length() - 4) + "/mcp";
            log.debug("MCP 协议: SSE, sseUrl={}, messageUrl={}", sseUrl, messageUrl);
        } else {
            protocol = Protocol.STREAMABLE_HTTP;
            streamableUrl = rawEndpoint;
            log.debug("MCP 协议: Streamable HTTP, url={}", streamableUrl);
        }
    }

    /**
     * 初始化握手：
     * - Streamable HTTP：直接 POST initialize
     * - SSE：先 GET /sse 拿 sessionId，再 POST initialize
     */
    public void initialize() throws Exception {
        if (protocol == Protocol.SSE) {
            sessionId = fetchSessionId();
            log.debug("MCP SSE sessionId={}", sessionId);
        }
        String body = buildRequest("initialize", 1, Map.of(
                "protocolVersion", "2024-11-05",
                "capabilities", Map.of("roots", Map.of("listChanged", true), "sampling", Map.of()),
                "clientInfo", Map.of("name", "hify-mcp-client", "version", "1.0.0")
        ));
        JsonNode result = postAndParseSse(body);
        log.debug("MCP initialize result: {}", result);
    }

    /** 列出工具名列表 */
    public List<String> listToolNames() throws Exception {
        String body = buildRequest("tools/list", 2, Map.of());
        JsonNode result = postAndParseSse(body);
        List<String> names = new ArrayList<>();
        JsonNode tools = result.path("tools");
        if (tools.isArray()) {
            tools.forEach(t -> names.add(t.path("name").asText()));
        }
        return names;
    }

    /** 列出工具详情 */
    public List<Map<String, Object>> listToolDetails() throws Exception {
        String body = buildRequest("tools/list", 2, Map.of());
        JsonNode result = postAndParseSse(body);
        List<Map<String, Object>> details = new ArrayList<>();
        JsonNode tools = result.path("tools");
        if (tools.isArray()) {
            tools.forEach(t -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", t.path("name").asText());
                m.put("description", t.path("description").asText(""));
                if (!t.path("inputSchema").isMissingNode()) {
                    m.put("inputSchema", t.path("inputSchema"));
                }
                details.add(m);
            });
        }
        return details;
    }

    /** 调用工具 */
    public String callTool(String toolName, Map<String, Object> arguments) throws Exception {
        String body = buildRequest("tools/call", 3, Map.of(
                "name", toolName,
                "arguments", arguments != null ? arguments : Map.of()
        ));
        JsonNode result = postAndParseSse(body);
        JsonNode content = result.path("content");
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            content.forEach(c -> {
                if ("text".equals(c.path("type").asText())) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(c.path("text").asText());
                }
            });
            return sb.toString();
        }
        return result.toString();
    }

    // ── 内部：SSE 握手，获取 sessionId ───────────────────────────

    private String fetchSessionId() throws Exception {
        CompletableFuture<String> future = new CompletableFuture<>();

        Request request = new Request.Builder()
                .url(sseUrl)
                .header("Accept", "text/event-stream")
                .build();

        // 用普通 OkHttp 流式读取，不依赖 okhttp-sse 扩展
        OkHttpClient sseClient = httpClient.newBuilder()
                .readTimeout(SSE_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
                .build();

        new Thread(() -> {
            try (Response response = sseClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    future.completeExceptionally(
                            new RuntimeException("SSE 连接失败: HTTP " + response.code()));
                    return;
                }
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.body().byteStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        // Spring AI MCP SSE 格式：
                        // event: endpoint
                        // data: /api/v1/mcp?sessionId=xxx
                        if (line.startsWith("data:")) {
                            String data = line.substring(5).trim();
                            if (data.contains("sessionId=")) {
                                String sid = data.substring(data.indexOf("sessionId=") + 10);
                                // 去掉可能的其他参数
                                if (sid.contains("&")) sid = sid.substring(0, sid.indexOf("&"));
                                future.complete(sid);
                                return;
                            }
                        }
                    }
                    future.completeExceptionally(
                            new RuntimeException("SSE 响应中未找到 sessionId"));
                }
            } catch (Exception e) {
                if (!future.isDone()) {
                    future.completeExceptionally(e);
                }
            }
        }, "mcp-sse-handshake").start();

        try {
            return future.get(SSE_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("SSE 握手超时，未收到 sessionId");
        }
    }

    // ── 内部：发请求、解析 SSE 响应 ──────────────────────────────

    private String buildRequest(String method, int id, Map<String, Object> params) throws Exception {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("jsonrpc", "2.0");
        req.put("method", method);
        req.put("id", id);
        req.put("params", params);
        return objectMapper.writeValueAsString(req);
    }

    private JsonNode postAndParseSse(String jsonBody) throws Exception {
        String url = resolvePostUrl();

        Request.Builder reqBuilder = new Request.Builder()
                .url(url)
                .post(RequestBody.create(jsonBody, JSON))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");

        try (Response response = httpClient.newCall(reqBuilder.build()).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = "";
                if (response.body() != null) {
                    try {
                        errorBody = response.body().string();
                        if (errorBody.length() > 300) errorBody = errorBody.substring(0, 300) + "...";
                    } catch (Exception ignored) {}
                }
                throw new RuntimeException(String.format(
                        "MCP 请求失败: HTTP %d | url=%s | %s",
                        response.code(), url, errorBody));
            }

            if (response.body() == null) {
                throw new RuntimeException("MCP 响应体为空");
            }

            String contentType = response.header("Content-Type", "");

            // SSE 格式解析
            if (contentType != null && contentType.contains("text/event-stream")) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.body().byteStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data:")) {
                            String data = line.substring(5).trim();
                            if (data.isEmpty()) continue;
                            JsonNode node = objectMapper.readTree(data);
                            if (!node.path("error").isMissingNode()) {
                                throw new RuntimeException("MCP 错误: " + node.path("error"));
                            }
                            return node.path("result");
                        }
                    }
                }
                throw new RuntimeException("MCP SSE 响应中未找到 data 事件");
            }

            // 普通 JSON 响应
            JsonNode node = objectMapper.readTree(response.body().string());
            if (!node.path("error").isMissingNode()) {
                throw new RuntimeException("MCP 错误: " + node.path("error"));
            }
            return node.path("result");
        }
    }

    /**
     * 根据协议决定 POST 地址：
     * - Streamable HTTP → 直接用 endpoint
     * - SSE → messageUrl?sessionId=xxx
     */
    private String resolvePostUrl() {
        if (protocol == Protocol.STREAMABLE_HTTP) {
            return streamableUrl;
        }
        if (sessionId == null) {
            throw new IllegalStateException("SSE 协议必须先调用 initialize() 获取 sessionId");
        }
        return messageUrl + "?sessionId=" + sessionId;
    }

    @Override
    public void close() {
        // OkHttp 连接池自动管理
    }
}