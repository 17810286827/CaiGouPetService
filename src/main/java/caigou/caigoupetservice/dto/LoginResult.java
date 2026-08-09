package caigou.caigoupetservice.dto;

/**
 * 登录/注册成功结果:JWT token + 用户视图
 */
public record LoginResult(String token, UserView user) {
}
