package caigou.caigoupetservice.dto;

/**
 * 修改密码请求体
 */
public record ChangePasswordRequest(String oldPassword, String newPassword) {
}
