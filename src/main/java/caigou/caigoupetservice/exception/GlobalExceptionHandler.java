package caigou.caigoupetservice.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 全局异常处理:统一返回 {error:"中文信息"} + 状态码,与 Express 响应风格一致
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常:按 ApiException 携带的状态码与信息返回
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> handleApi(ApiException e) {
        return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
    }

    /**
     * 兜底异常:记录日志并返回 500,避免泄露内部细节
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleOther(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.status(500).body(Map.of("error", "服务器内部错误"));
    }
}
