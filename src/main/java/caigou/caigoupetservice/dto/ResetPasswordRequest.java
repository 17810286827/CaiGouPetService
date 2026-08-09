package caigou.caigoupetservice.dto;

/**
 * 重置密码请求体
 */
public record ResetPasswordRequest(String token, Long uid, String newPassword) {
}
