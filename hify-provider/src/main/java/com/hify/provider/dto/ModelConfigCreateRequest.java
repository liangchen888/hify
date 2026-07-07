package com.hify.provider.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ModelConfigCreateRequest {

    @NotBlank(message = "模型名称不能为空")
    private String name;

    @NotBlank(message = "模型 ID 不能为空")
    private String modelId;

    @Min(value = 1, message = "上下文大小不能小于 1")
    private Integer contextSize = 4096;
}
