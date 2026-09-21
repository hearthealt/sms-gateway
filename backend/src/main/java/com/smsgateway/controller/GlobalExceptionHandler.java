package com.smsgateway.controller;

import com.smsgateway.exception.EnrollTokenRequiredException;
import com.smsgateway.service.notify.NotifyNotConfiguredException;
import com.smsgateway.exception.EnrollmentRequiredException;
import com.smsgateway.model.dto.ApiResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
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

    /**
     * 重注册时没能证明设备身份。
     *
     * <p>用 403 而不是 400：这不是「请求写错了」，而是「你没证明你是这台设备」。
     * 两者的区别对设备端有实际影响 —— 设备端把 400 归为终态直接放弃，
     * 403 才能把「去控制台签一张恢复码」这条可执行的提示带到现场。
     */
    @ExceptionHandler(EnrollmentRequiredException.class)
    public ResponseEntity<ApiResult<Void>> handleEnrollmentRequired(EnrollmentRequiredException e) {
        log.warn("Enrollment required: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResult.error(403, e.getMessage()));
    }

    /**
     * 首次注册没能出示本服务器的接入口令。
     *
     * <p>与上面那条同样是 403，理由也一样：这不是「请求写错了」，而是「你没被允许接入」，
     * 而设备端只有拿到 403 才会把「去管理后台重新扫一张码」这条提示带到现场，400 会被它
     * 当成终态错误直接放弃。
     */
    @ExceptionHandler(EnrollTokenRequiredException.class)
    public ResponseEntity<ApiResult<Void>> handleEnrollTokenRequired(EnrollTokenRequiredException e) {
        log.warn("Enrollment token required: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResult.error(403, e.getMessage()));
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
     * <p>注意：设备端的探活以前打的正是根路径（那时只要拿到任何 HTTP 响应就算网络可达），
     * 所以这条一度是预期流量。现在探活改打 /api/health 并校验服务标识，不再是常态 ——
     * 再看到它，多半是路径真的写错了。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResult<Void>> handleNoResource(NoResourceFoundException e) {
        log.warn("No handler for path: {}", e.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResult.error(404, "接口不存在: " + e.getResourcePath()));
    }

    /**
     * 转发功能没配好就被人用了（通常是没设那两个环境变量）。
     *
     * <p>用 **503** 而不是 500，且把异常的消息**原样**返回：那条消息本身就是操作指引
     * （设哪两个变量、密钥怎么生成、要重启），掉进 500 兜底会变成一句
     * 「服务器内部错误」，现场完全想不到是配置没做完。
     */
    @ExceptionHandler(NotifyNotConfiguredException.class)
    public ResponseEntity<ApiResult<Void>> handleNotifyNotConfigured(NotifyNotConfiguredException e) {
        log.warn("Notify not configured: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResult.error(503, e.getMessage()));
    }

    /**
     * 异步请求超时。实际只会在一种情况下出现：停机时 Tomcat 关连接器，会把还挂着的
     * 异步请求逐个强制置为超时（AbstractProtocol.stop 里对 waitingProcessors
     * 逐个 timeoutAsync(-1)），管理后台那条 SSE 事件流因此每次重启都撞上一发。
     *
     * <p>必须单独接住，不能掉进下面那个兜底：兜底返回 500 + ApiResult，可这条响应的
     * Content-Type 已经写死 text/event-stream，没有转换器能写 ApiResult
     * （HttpMessageNotWritableException: No converter for [...] with preset Content-Type
     * 'text/event-stream'）—— 于是一次正常收线在日志里变成两坨 ERROR 栈，把真错误淹掉。
     *
     * <p>不返回 body：连接那头要么已经走了，要么流已经结束，写了也没人收；而且带 body
     * 会原样撞上上面那个转换器问题。状态码仍按 Spring 自己的约定给 503
     * （DefaultHandlerExceptionResolver 处理这个异常就是 503），响应若已提交它会被忽略。
     */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ResponseEntity<Void> handleAsyncTimeout(AsyncRequestTimeoutException e) {
        log.debug("Async request timed out (client gone, or server shutting down)");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<Void>> handleOther(Exception e) {
        log.error("Unhandled error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResult.error(500, "服务器内部错误"));
    }
}
