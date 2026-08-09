package caigou.caigoupetservice.dto;

/**
 * 扫码登录初始化响应
 * 字段名使用下划线(session_token / qr_data_url / expires_in),与 Express 响应一致
 */
public record QrInitResponse(String session_token, String qr_data_url, int expires_in) {
}
