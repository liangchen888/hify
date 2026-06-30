package com.hify.provider.controller;

import com.hify.common.dto.PageResult;
import com.hify.common.dto.Result;
import com.hify.provider.dto.*;
import com.hify.provider.entity.ModelConfig;
import com.hify.provider.entity.Provider;
import com.hify.provider.service.ProviderConnectionTestService;
import com.hify.provider.service.ProviderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/providers")
@RequiredArgsConstructor
public class ProviderController {

    private final ProviderService providerService;
    private final ProviderConnectionTestService connectionTestService;

    @PostMapping
    public Result<Provider> create(@Valid @RequestBody ProviderCreateRequest request) {
        return Result.ok(providerService.create(request));
    }

    @GetMapping
    public Result<PageResult<ProviderDetailResponse>> list(ProviderQueryRequest request) {
        return providerService.list(request);
    }

    @GetMapping("/{id}")
    public Result<ProviderDetailResponse> detail(@PathVariable Long id) {
        return Result.ok(providerService.getDetail(id));
    }

    @PutMapping("/{id}")
    public Result<Provider> update(@PathVariable Long id,
                                   @Valid @RequestBody ProviderUpdateRequest request) {
        return Result.ok(providerService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        providerService.delete(id);
        return Result.ok();
    }

    @PostMapping("/{id}/test-connection")
    public Result<ConnectionTestResult> testConnection(@PathVariable Long id) {
        return Result.ok(connectionTestService.testById(id));
    }

    /** 从远程提供商拉取模型 ID 列表（用于添加模型时快速填充）*/
    @GetMapping("/{id}/fetch-models")
    public Result<List<String>> fetchRemoteModels(@PathVariable Long id) {
        return Result.ok(connectionTestService.fetchRemoteModels(id));
    }

    // ── 模型配置 CRUD ──────────────────────────────────────

    @GetMapping("/{id}/models")
    public Result<List<ModelConfig>> listModels(@PathVariable Long id) {
        return Result.ok(providerService.listModels(id));
    }

    @PostMapping("/{id}/models")
    public Result<ModelConfig> addModel(@PathVariable Long id,
                                        @Valid @RequestBody ModelConfigCreateRequest request) {
        return Result.ok(providerService.addModel(id, request));
    }

    @PutMapping("/{id}/models/{modelId}")
    public Result<ModelConfig> updateModel(@PathVariable Long id,
                                           @PathVariable Long modelId,
                                           @Valid @RequestBody ModelConfigUpdateRequest request) {
        return Result.ok(providerService.updateModel(id, modelId, request));
    }

    @DeleteMapping("/{id}/models/{modelId}")
    public Result<Void> deleteModel(@PathVariable Long id, @PathVariable Long modelId) {
        providerService.deleteModel(id, modelId);
        return Result.ok();
    }
}
