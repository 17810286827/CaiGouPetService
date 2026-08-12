package caigou.caigoupetservice.exception;

import lombok.Getter;

/**
 * 业务异常:携带 HTTP 状态码与中文错误信息
 * 由 GlobalExceptionHandler 统一捕获并返回 {error:"..."} 响应
 * 需要非标准响应体(如插件校验失败 {error,details,warnings})时,用三参构造传入 body,handler 原样序列化
 */
@Getter
public class ApiException extends RuntimeException {

    private final int status;

    /** 可选:非标准响应体(不为 null 时优先于 {error:message} 返回,用于携带 details/warnings) */
    private final Object body;

    /**
     * 构造业务异常
     * @param status HTTP 状态码
     * @param message 错误信息(中文)
     */
    public ApiException(int status, String message) {
        this(status, message, null);
    }

    /**
     * 构造带自定义响应体的业务异常(响应体为 null 时退化走默认 {error:message})
     * @param status HTTP 状态码
     * @param message 错误信息(日志/兜底用)
     * @param body 响应体(如 {error,details,warnings} Map),会替代默认 {error} 结构
     */
    public ApiException(int status, String message, Object body) {
        super(message);
        this.status = status;
        this.body = body;
    }
}
