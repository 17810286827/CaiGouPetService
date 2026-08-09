package caigou.caigoupetservice.config;

import io.jsonwebtoken.security.Keys;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 通用 Bean 配置:密码编码器 + JWT 签名密钥
 */
@Configuration
public class BeanConfig {

    /**
     * BCrypt 密码编码器,与 Express 端 bcryptjs(10 轮)互验
     * 用于密码哈希与校验
     */
    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * JWT HMAC 签名密钥
     * jjwt 对 HS256 要求密钥 >=32 字节,将配置的 secret 经 SHA-256 派生为定长 32 字节,
     * 使任意长度的 secret 都能安全使用
     */
    @Bean
    public SecretKey jwtSecretKey(JwtProperties props) throws NoSuchAlgorithmException {
        byte[] keyBytes = MessageDigest.getInstance("SHA-256")
                .digest(props.getSecret().getBytes(StandardCharsets.UTF_8));
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
