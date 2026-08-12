package caigou.caigoupetservice.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

/**
 * 全局异常处理:统一返回 {error:"中文信息"} + 状态码,与 Express 响应风格一致
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常:按 ApiException 携带的状态码返回;若带自定义 body(如插件校验的 details/warnings)则原样序列化,
     * 否则默认返回 {error:"中文信息"},与 Express 响应风格一致
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<?> handleApi(ApiException e) {
        Object body = e.getBody() != null ? e.getBody() : Map.of("error", e.getMessage());
        return ResponseEntity.status(e.getStatus()).body(body);
    }

    /**
     * 上传文件超限:multipart 解析阶段(进入 handler 前)由容器/解析器抛出,
     * 契约要求返回 413 "文件大小超出限制",而非走兜底 500
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> handleMaxUpload(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(413).body(Map.of("error", "文件大小超出限制"));
    }

    /**
     * 请求不存在接口(未迁移模块路径打到 Java):Spring 找不到 controller 抛
     * NoResourceFoundException,契约返回 404 + {error},而非走兜底 500 刷 ERROR 堆栈
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> handleNoResource(NoResourceFoundException e) {
        return ResponseEntity.status(404).body(Map.of("error", "接口不存在"));
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
