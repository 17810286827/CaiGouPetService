package caigou.caigoupetservice.config;

import caigou.caigoupetservice.interceptor.JwtAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置:注册 JWT 认证拦截器 + 跨域(CORS)
 * 认证白名单:注册/登录/扫码/找回密码等公开接口放行
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final JwtAuthInterceptor jwtAuthInterceptor;

    /**
     * 注册 JWT 拦截器:对 /api/** 生效,白名单式放行公开认证接口
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtAuthInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/register",
                        "/api/auth/login",
                        "/api/auth/forgot-password",
                        "/api/auth/reset-password",
                        "/api/auth/qrcode/init",
                        "/api/auth/qrcode/poll",
                        "/api/auth/qrcode/confirm");
    }

    /**
     * 跨域配置,复刻 Express 的 cors({origin:'*'})
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }

    /**
     * 静态资源映射:上传目录经 /api/files/** 与 /uploads/** 提供访问,复刻 Express express.static(upload.dir)
     */
    @Override
    public void addResourceHandlers(org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry registry) {
        String uploadDir = System.getProperty("upload.dir", "./uploads");
        registry.addResourceHandler("/api/files/**").addResourceLocations("file:" + uploadDir + "/");
        registry.addResourceHandler("/uploads/**").addResourceLocations("file:" + uploadDir + "/");
    }
}
