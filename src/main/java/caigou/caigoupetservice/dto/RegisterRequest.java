package caigou.caigoupetservice.dto;

/**
 * 注册请求体
 */
public record RegisterRequest(String username, String password, String nickname, String email) {
}
