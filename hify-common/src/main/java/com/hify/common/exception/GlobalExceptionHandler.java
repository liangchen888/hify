package com.hify.common.exception;

import com.hify.common.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleBizException(BizException ex) {
        log.warn("业务异常: code={}, message={}", ex.getErrorCode().getCode(), ex.getMessage());
        return Result.fail(ex.getErrorCode().getCode(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleValidationException(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败: {}", message);
        return Result.fail(ErrorCode.PARAM_ERROR.getCode(), message);
    }

    /**
     * 静态资源不存在（如 /favicon.ico），降级为 WARN，不打 ERROR 堆栈。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNoResource(NoResourceFoundException ex) {
        log.warn("资源不存在: {}", ex.getResourcePath());
        return Result.fail(ErrorCode.RESOURCE_NOT_FOUND.getCode(), "资源不存在");
    }

    /**
     * 内容类型协商失败（406）。
     * 常见原因：客户端 Accept 头与接口 produces 不匹配。
     * 直接返回 406，不再尝试写 JSON（写 JSON 也会失败导致二次异常）。
     */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    @ResponseStatus(HttpStatus.NOT_ACCEPTABLE)
    public void handleMediaTypeNotAcceptable(HttpMediaTypeNotAcceptableException ex) {
        log.warn("内容类型协商失败: {} — 请检查请求的 Accept 头与接口 produces 是否匹配",
                ex.getMessage());
        // 故意不返回 body：因为已经无法写出任何 MediaType，返回空响应即可
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleException(Exception ex) {
        log.error("系统异常", ex);
        return Result.fail(ErrorCode.SYSTEM_ERROR.getCode(), ErrorCode.SYSTEM_ERROR.getMessage());
    }
}
