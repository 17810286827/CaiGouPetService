package caigou.caigoupetservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * JWT 配置属性,绑定 application.yaml 中 jwt.* 配置项
 * secret: 签名密钥(任意长度,使用时经 SHA-256 派生为 32 字节)
 * expiresIn: token 有效期,如 "7d" 自动绑定为 Duration
 */
@Data
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    /** JWT 签名密钥 */
    private String secret;

    /** token 有效期 */
    private Duration expiresIn;
}
