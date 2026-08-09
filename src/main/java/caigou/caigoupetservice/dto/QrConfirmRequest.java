package caigou.caigoupetservice.dto;

/**
 * 扫码登录确认请求体(手机端确认)
 */
public record QrConfirmRequest(String session, String username, String password) {
}
