package caigou.caigoupetservice.exception;

import lombok.Getter;

/**
 * 业务异常:携带 HTTP 状态码与中文错误信息
 * 由 GlobalExceptionHandler 统一捕获并返回 {error:"..."} 响应
 */
@Getter
public class ApiException extends RuntimeException {

    private final int status;

    /**
     * 构造业务异常
     * @param status HTTP 状态码
     * @param message 错误信息(中文)
     */
    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }
}
