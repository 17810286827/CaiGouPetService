package caigou.caigoupetservice.service;

import caigou.caigoupetservice.dto.QrConfirmRequest;
import caigou.caigoupetservice.dto.QrInitResponse;
import caigou.caigoupetservice.dto.UserView;
import caigou.caigoupetservice.entity.User;
import caigou.caigoupetservice.exception.ApiException;
import caigou.caigoupetservice.mapper.UserMapper;
import caigou.caigoupetservice.util.QrCodeGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 扫码登录服务:会话内存管理 + 二维码生成
 * 会话存 ConcurrentHashMap,TTL 120 秒,每 30 秒定时清理(复刻 Express 内存 Map 行为)
 */
@Service
@RequiredArgsConstructor
public class QrLoginService {

    /** 扫码会话有效期:120 秒 */
    private static final long TTL_MILLIS = 120_000L;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final Map<String, QrSession> sessions = new ConcurrentHashMap<>();
    private final JwtService jwtService;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    /**
     * 初始化扫码会话:生成 session_token 与二维码 PNG dataURL
     */
    public QrInitResponse init() {
        String sessionToken = randomHex(32);
        long now = System.currentTimeMillis();
        sessions.put(sessionToken, new QrSession("pending", now + TTL_MILLIS, now, null));
        // 二维码承载内容与 Express 完全一致
        String qrContent = "{\"type\":\"caigopet_login\",\"session\":\"" + sessionToken
                + "\",\"ts\":" + now + "}";
        String qrDataUrl = QrCodeGenerator.toDataUrl(qrContent);
        return new QrInitResponse(sessionToken, qrDataUrl, 120);
    }

    /**
     * 轮询扫码状态
     * 过期返回 {status:expired};已确认且用户存在则签发 token 返回 success;否则返回当前状态
     */
    public Map<String, Object> poll(String session) {
        if (session == null || session.isBlank()) {
            throw new ApiException(400, "Missing session token");
        }
        QrSession qs = sessions.get(session);
        // 会话不存在或已过期:清理并返回 expired
        if (qs == null || System.currentTimeMillis() > qs.expiresAt) {
            sessions.remove(session);
            return Map.of("status", "expired");
        }
        if ("confirmed".equals(qs.status) && qs.userId != null) {
            User user = userMapper.findById(qs.userId);
            if (user != null) {
                String token = jwtService.generateToken(user.getId());
                sessions.remove(session);
                // 用 LinkedHashMap 精确控制成功响应只含 status/token/user 三个字段
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", "success");
                result.put("token", token);
                result.put("user", UserView.from(user));
                return result;
            }
        }
        return Map.of("status", qs.status);
    }

    /**
     * 手机端确认登录:校验用户名与密码后将会话置为已确认
     */
    public void confirm(QrConfirmRequest req) {
        if (isBlank(req.session())) {
            throw new ApiException(400, "缺少 session 参数");
        }
        QrSession qs = sessions.get(req.session());
        if (qs == null) {
            throw new ApiException(404, "会话不存在或已过期");
        }
        if (System.currentTimeMillis() > qs.expiresAt) {
            sessions.remove(req.session());
            throw new ApiException(404, "二维码已过期");
        }
        User user = userMapper.findByUsername(req.username());
        if (user == null) {
            throw new ApiException(401, "用户不存在");
        }
        if (!passwordEncoder.matches(req.password(), user.getPassword())) {
            throw new ApiException(401, "密码错误");
        }
        qs.status = "confirmed";
        qs.userId = user.getId();
    }

    /**
     * 定时清理过期会话,复刻 Express 的 30 秒 setInterval
     */
    @Scheduled(fixedDelay = 30_000)
    public void cleanup() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(e -> now > e.getValue().expiresAt);
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 生成指定字节数的十六进制随机串 */
    private String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        SECURE_RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /**
     * 扫码会话内部模型:状态/过期时间/创建时间/确认用户
     * 字段用 volatile 保证多线程可见性(confirm 写, poll 读)
     */
    private static class QrSession {
        volatile String status;
        final long expiresAt;
        final long createdAt;
        volatile Long userId;

        QrSession(String status, long expiresAt, long createdAt, Long userId) {
            this.status = status;
            this.expiresAt = expiresAt;
            this.createdAt = createdAt;
            this.userId = userId;
        }
    }
}
