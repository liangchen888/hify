package com.hify.provider.service;

import com.hify.common.dto.PageResult;
import com.hify.common.dto.Result;
import com.hify.provider.dto.ModelConfigCreateRequest;
import com.hify.provider.dto.ModelConfigUpdateRequest;
import com.hify.provider.dto.ProviderCreateRequest;
import com.hify.provider.dto.ProviderDetailResponse;
import com.hify.provider.dto.ProviderQueryRequest;
import com.hify.provider.dto.ProviderUpdateRequest;
import com.hify.provider.entity.ModelConfig;
import com.hify.provider.entity.Provider;

import java.util.List;

public interface ProviderService {

    Provider create(ProviderCreateRequest request);

    Provider update(Long id, ProviderUpdateRequest request);

    void delete(Long id);

    void toggleEnabled(Long id);

    ProviderDetailResponse getDetail(Long id);

    Result<PageResult<ProviderDetailResponse>> list(ProviderQueryRequest request);

    /** 跨模块调用：校验 modelConfigId 存在且已启用 */
    ModelConfig getEnabledModelConfigOrThrow(Long modelConfigId);

    // ── 模型配置 CRUD ──────────────────────────────────────

    List<ModelConfig> listModels(Long providerId);

    ModelConfig addModel(Long providerId, ModelConfigCreateRequest request);

    ModelConfig updateModel(Long providerId, Long modelId, ModelConfigUpdateRequest request);

    void deleteModel(Long providerId, Long modelId);
}
