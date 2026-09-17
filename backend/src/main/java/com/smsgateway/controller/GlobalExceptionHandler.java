package com.smsgateway.controller;

import com.smsgateway.model.dto.ApiResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 统一异常响应，保证前端拿到的始终是 ApiResult 结构。
 * 不加这层的话，校验失败/业务异常会返回 Spring 默认的错误 JSON，
 * 前端拦截器无法按约定的 code 处理。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResult<Void>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("Business error: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResult.error(400, e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResult<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("Validation error: {}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResult.error(400, message));
    }

    /**
     * 缺必填 query 参数。
     *
     * <p>不加这个 handler 的话，它会掉进下面那个 Exception 兜底，对外返回
     * 500「服务器内部错误」—— 把「少传参数」报成「服务端炸了」，
     * 调用方完全无从排查。类型不匹配（如 timeout=abc）同理。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResult<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("Missing request parameter: {}", e.getParameterName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResult.error(400, "缺少必填参数: " + e.getParameterName()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResult<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("Argument type mismatch: name={}, value={}", e.getName(), e.getValue());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResult.error(400, "参数格式错误: " + e.getName()));
    }

    /**
     * 路径不存在。
     *
     * <p>Spring 6.1 起，未匹配到任何路由的请求会抛 NoResourceFoundException
     * （更早的版本是 NoHandlerFoundException，由静态资源处理器兜底后抛出）。
     * 不单独接住就会掉进下面的兜底，把「这个接口不存在」报成 500「服务器内部错误」，
     * 排查时会往服务端故障方向找，而实际只是路径写错或接口还没部署。
     *
     * <p>设备端「测试连接 / 自检」的探活**故意**打根路径（拿到任何 HTTP 状态码都算
     * 网络可达，不依赖任何具体接口），所以这条会经常出现，属于预期流量，不是故障。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResult<Void>> handleNoResource(NoResourceFoundException e) {
        log.warn("No handler for path: {}", e.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResult.error(404, "接口不存在: " + e.getResourcePath()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<Void>> handleOther(Exception e) {
        log.error("Unhandled error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResult.error(500, "服务器内部错误"));
    }
}
