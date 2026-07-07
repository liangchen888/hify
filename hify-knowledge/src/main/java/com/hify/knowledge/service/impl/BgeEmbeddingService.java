package com.hify.knowledge.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgvector.PGvector;
import com.hify.knowledge.service.EmbeddingService;


import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;

/**
 * BGE嵌入服务 - 调用智源BGE-large-zh-v1.5模型
 * 支持本地部署或API调用
 */

@Service

public class BgeEmbeddingService implements EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(BgeEmbeddingService.class);

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    
    @Value("${embedding.bge.endpoint:http://localhost:8000/v1/embeddings}")
    private String bgeEndpoint;
    
    @Value("${embedding.bge.api-key:}")
    private String apiKey;
    
    @Value("${embedding.bge.model:BAAI/bge-large-zh-v1.5}")
    private String model;
    
    // BGE模型输出维度（BGE-large-zh-v1.5 = 1024维，部分版本768维）
    private static final int EMBEDDING_DIMENSION = 768;
    
    public BgeEmbeddingService(OkHttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }
    
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
            // 构建请求体
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("input", texts);
            requestBody.put("model", model);
            
            String jsonBody = objectMapper.writeValueAsString(requestBody);
            
            // 创建HTTP请求
            Request.Builder builder = new Request.Builder()
                .url(bgeEndpoint)
                .post(RequestBody.create(jsonBody, MediaType.get("application/json")));
            
            // 添加API Key（如果配置了）
            if (apiKey != null && !apiKey.isBlank()) {
                builder.addHeader("Authorization", "Bearer " + apiKey);
            }
            
            Request request = builder.build();
            
            // 发送请求
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("BGE API请求失败: {} {}", response.code(), response.message());
                    throw new RuntimeException("BGE API返回错误: " + response.code());
                }
                
                String responseBody = response.body().string();
                Map<String, Object> responseMap = objectMapper.readValue(responseBody, Map.class);
                
                // 解析响应
                List<Map<String, Object>> embeddings = 
                    (List<Map<String, Object>>) responseMap.get("data");
                
                List<PGvector> vectors = new ArrayList<>();
                for (Map<String, Object> embedding : embeddings) {
                    List<Double> vector = (List<Double>) embedding.get("embedding");
                    // 确保维度正确
                    if (vector.size() != EMBEDDING_DIMENSION) {
                        log.warn("向量维度不匹配: 期望{}, 实际{}", EMBEDDING_DIMENSION, vector.size());
                    }
                    // Convert Double[] to float[] for PGvector
                    float[] floatArray = new float[vector.size()];
                    for (int i = 0; i < vector.size(); i++) {
                        floatArray[i] = vector.get(i).floatValue();
                    }
                    vectors.add(new PGvector(floatArray));
                }
                
                log.debug("BGE嵌入完成: 文本数={}, 维度={}", texts.size(), EMBEDDING_DIMENSION);
                return vectors;
                
            } catch (IOException e) {
                log.error("BGE API请求异常", e);
                throw new RuntimeException("调用BGE API失败: " + e.getMessage(), e);
            }
            
        } catch (Exception e) {
            log.error("批量嵌入失败", e);
            throw new RuntimeException("嵌入向量化失败: " + e.getMessage(), e);
        }
    }
}
