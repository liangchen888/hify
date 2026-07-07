package com.hify.knowledge.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.knowledge.service.EmbeddingService;
import com.pgvector.PGvector;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;

/**
 * BGE 嵌入服务 —— 通过 HTTP 调用兼容 OpenAI Embeddings 格式的本地 BGE 服务。
 *
 * 支持的后端：
 *   - Ollama：  http://localhost:11434/v1/embeddings  (模型: nomic-embed-text / bge-large-zh-v1.5)
 *   - 自定义 Flask/FastAPI：http://localhost:8000/v1/embeddings
 *
 * 向量维度由响应自动推断，无需在代码中硬编码。
 * application.yml 配置项（均支持环境变量覆盖）：
 *   embedding.bge.endpoint   默认 http://localhost:11434/v1/embeddings
 *   embedding.bge.api-key    默认空（Ollama 本地不需要）
 *   embedding.bge.model      默认 nomic-embed-text
 */
@Service
public class BgeEmbeddingService implements EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(BgeEmbeddingService.class);

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${embedding.bge.endpoint:http://localhost:11434/v1/embeddings}")
    private String bgeEndpoint;

    @Value("${embedding.bge.api-key:}")
    private String apiKey;

    @Value("${embedding.bge.model:nomic-embed-text}")
    private String model;

    public BgeEmbeddingService(OkHttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    // ── 公开接口实现 ────────────────────────────────────────────

    @Override
    public PGvector embed(String text) {
        List<PGvector> vectors = embedBatch(List.of(text));
        return vectors.isEmpty() ? null : vectors.get(0);
    }

    @Override
    public List<PGvector> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return new ArrayList<>();
        }

        try {
            // 构造请求 JSON
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("input", texts);
            requestBody.put("model", model);
            String jsonBody = objectMapper.writeValueAsString(requestBody);

            Request.Builder builder = new Request.Builder()
                .url(bgeEndpoint)
                .post(RequestBody.create(jsonBody, MediaType.get("application/json")));

            // Bearer Token（Ollama 本地部署不需要，远程部署时按需配置）
            if (apiKey != null && !apiKey.isBlank()) {
                builder.addHeader("Authorization", "Bearer " + apiKey);
            }

            Request request = builder.build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errBody = response.body() != null ? response.body().string() : "";
                    log.error("BGE API 请求失败 status={} body={}", response.code(), errBody);
                    throw new RuntimeException("BGE API 返回错误: " + response.code() + " " + errBody);
                }

                String responseBody = response.body().string();
                return parseEmbeddingResponse(responseBody, texts.size());
            }

        } catch (IOException e) {
            log.error("BGE API 网络异常 endpoint={}", bgeEndpoint, e);
            throw new RuntimeException("调用 BGE API 网络异常: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("批量向量化失败", e);
            throw new RuntimeException("向量化失败: " + e.getMessage(), e);
        }
    }

    // ── 私有方法 ────────────────────────────────────────────────

    /**
     * 解析 OpenAI Embeddings 格式响应：
     * {
     *   "object": "list",
     *   "data": [
     *     { "object": "embedding", "index": 0, "embedding": [0.1, 0.2, ...] },
     *     ...
     *   ],
     *   "model": "...",
     *   "usage": { ... }
     * }
     */
    @SuppressWarnings("unchecked")
    private List<PGvector> parseEmbeddingResponse(String responseBody, int expectedCount) throws Exception {
        Map<String, Object> responseMap = objectMapper.readValue(responseBody, Map.class);
        List<Map<String, Object>> dataList = (List<Map<String, Object>>) responseMap.get("data");

        if (dataList == null || dataList.isEmpty()) {
            log.error("BGE 响应中 data 为空: {}", responseBody);
            throw new RuntimeException("BGE 响应 data 字段为空");
        }
        if (dataList.size() != expectedCount) {
            log.warn("BGE 响应数量不匹配: 期望={}, 实际={}", expectedCount, dataList.size());
        }

        // 按 index 排序，保证与输入顺序一致
        dataList.sort(Comparator.comparingInt(m -> ((Number) m.get("index")).intValue()));

        List<PGvector> vectors = new ArrayList<>(dataList.size());
        for (Map<String, Object> item : dataList) {
            List<Number> vector = (List<Number>) item.get("embedding");
            if (vector == null || vector.isEmpty()) {
                throw new RuntimeException("BGE 响应中某条 embedding 为空");
            }
            float[] floats = new float[vector.size()];
            for (int i = 0; i < vector.size(); i++) {
                floats[i] = vector.get(i).floatValue();
            }
            vectors.add(new PGvector(floats));
        }

        log.debug("BGE 向量化完成: 数量={}, 维度={}", vectors.size(),
            vectors.isEmpty() ? 0 : vectors.get(0).toArray().length);
        return vectors;
    }
}
