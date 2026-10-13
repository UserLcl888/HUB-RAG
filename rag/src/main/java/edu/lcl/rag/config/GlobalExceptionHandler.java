package edu.lcl.rag.config;

import edu.lcl.rag.dto.ApiResponse;
import edu.lcl.rag.exception.BizException;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBizException(BizException e) {
        log.warn("[业务异常] code={}，message={}", e.getCode(), e.getMessage());
        return ResponseEntity.status(e.getCode())
                .body(ApiResponse.error(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e, HttpServletResponse response) {
        // SSE 场景下"用户关页面/刷新/切会话"会让写流抛 IOException（Windows: 主机中的软件中止了一个已建立的连接）。
        // 这属于正常断开，不是系统异常：既不能往 text/event-stream 里写 JSON（会抛 HttpMessageNotWritableException），
        // 也不该打 ERROR 堆栈，因此这里直接静默返回（null = 已处理、无响应体）。
        if (isClientDisconnect(e) || isCommitted(response) || isEventStream(response)) {
            log.debug("[客户端断开] {}", e.getMessage());
            return null;
        }
        log.error("[系统异常] {}", e.getMessage(), e);
        return ResponseEntity.status(500)
                .body(ApiResponse.error(500, "系统内部错误，请稍后重试"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
    }

    /** 客户端主动断开连接的典型异常（Tomcat / Spring 异步请求各自的类型）。 */
    private boolean isClientDisconnect(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String name = t.getClass().getName();
            if (name.contains("ClientAbortException")
                    || name.contains("AsyncRequestNotUsableException")
                    || name.contains("EofException")
                    || name.contains("ResponseBodyEmitter")) {
                return true;
            }
        }
        return false;
    }

    private boolean isCommitted(HttpServletResponse response) {
        return response != null && response.isCommitted();
    }

    private boolean isEventStream(HttpServletResponse response) {
        String contentType = response == null ? null : response.getContentType();
        return contentType != null && contentType.contains(MediaType.TEXT_EVENT_STREAM_VALUE);
    }
}
