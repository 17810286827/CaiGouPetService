package caigou.caigoupetservice.service;

import caigou.caigoupetservice.config.JwtProperties;
import caigou.caigoupetservice.exception.ApiException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;

/**
 * JWT 服务:签发与校验令牌
 * 签入 payload:userId;有效期来自配置(默认 7d)
 */
@Service
@RequiredArgsConstructor
public class JwtService {

    private final SecretKey secretKey;
    private final JwtProperties props;

    /**
     * 为用户签发 JWT
     * @param userId 用户主键
     * @return token 字符串
     */
    public String generateToken(Long userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("userId", userId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(props.getExpiresIn())))
                .signWith(secretKey)
                .compact();
    }

    /**
     * 校验并解析 token 中的 userId
     * 过期/无效分别抛出 401 业务异常
     * @param token JWT 字符串
     * @return userId
     */
    public Long parseUserId(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            // 小 id 可能被反序列化为 Integer,统一转 Long
            return claims.get("userId", Number.class).longValue();
        } catch (ExpiredJwtException e) {
            throw new ApiException(401, "令牌已过期");
        } catch (JwtException | IllegalArgumentException e) {
            throw new ApiException(401, "无效的认证令牌");
        }
    }
}
