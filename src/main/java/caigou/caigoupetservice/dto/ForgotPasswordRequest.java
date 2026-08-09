package caigou.caigoupetservice.dto;

/**
 * 找回密码请求体:account 为用户名或邮箱
 */
public record ForgotPasswordRequest(String account) {
}
