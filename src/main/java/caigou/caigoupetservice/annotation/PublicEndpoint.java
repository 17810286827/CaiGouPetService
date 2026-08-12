package caigou.caigoupetservice.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记公开接口:标注了此注解的请求方法无需认证直接放行
 * 镜像 Express 未挂 authMiddleware 的公开路由语义
 * Retention 需为 RUNTIME,拦截器才能通过反射识别
 * 防线提示:拦截器对标注此注解的方法(不限 HTTP 方法)一律放行,
 * 仅限确实公开的端点,写接口(POST/DELETE/PUT)慎用,防止误标导致接口意外公开
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PublicEndpoint {
}
