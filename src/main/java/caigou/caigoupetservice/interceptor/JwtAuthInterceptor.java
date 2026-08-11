package caigou.caigoupetservice.interceptor;

import caigou.caigoupetservice.annotation.PublicEndpoint;
import caigou.caigoupetservice.dto.UserView;
import caigou.caigoupetservice.entity.User;
import caigou.caigoupetservice.exception.ApiException;
import caigou.caigoupetservice.mapper.UserMapper;
import caigou.caigoupetservice.service.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * JWT 认证拦截器:对受保护接口校验 Authorization Bearer token
 * 校验通过后查库复核用户状态,并把当前用户写入 request attribute 供 controller 使用
 * 镜像 Express middleware/auth.js 的行为
 */
@Component
@RequiredArgsConstructor
public class JwtAuthInterceptor implements HandlerInterceptor {

    private final JwtService jwtService;
    private final UserMapper userMapper;

    /**
     * 请求处理前校验 token
     * @return false 表示已写出错误响应,终止后续处理
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        // 非控制器方法(如静态资源)直接放行
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        // 公开只读 GET(@PublicEndpoint 注解)直接放行,镜像 Express 未加 authMiddleware 的公开路由
        if (isPublicGet(request, (HandlerMethod) handler)) {
            return true;
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return writeError(response, 401, "未提供认证令牌");
        }
        String token = header.substring("Bearer ".length());
        Long userId;
        try {
            userId = jwtService.parseUserId(token);
        } catch (ApiException e) {
            // 令牌过期/无效,按携带的状态码与文案返回
            return writeError(response, e.getStatus(), e.getMessage());
        }
        // 镜像 Express:验签后查库复核用户是否存在且未禁用
        User user = userMapper.findById(userId);
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            return writeError(response, 401, "用户不存在或已禁用");
        }
        // 当前用户写入 request attribute,controller 直接取用
        request.setAttribute("currentUserId", user.getId());
        request.setAttribute("currentUser", UserView.from(user));
        return true;
    }

    /** GET + 方法标注 @PublicEndpoint 时放行(公开只读接口) */
    private boolean isPublicGet(HttpServletRequest request, HandlerMethod handler) {
        if (!"GET".equals(request.getMethod())) {
            return false;
        }
        return handler.getMethod().isAnnotationPresent(PublicEndpoint.class);
    }

    /**
     * 写出统一格式的错误响应并终止请求
     */
    private boolean writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
        return false;
    }
}
