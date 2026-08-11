package caigou.caigoupetservice.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记公开接口:标注了此注解的请求方法无需认证直接放行
 * 镜像 Express 未挂 authMiddleware 的公开路由语义
 * Retention 需为 RUNTIME,拦截器才能通过反射识别
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PublicEndpoint {
}
