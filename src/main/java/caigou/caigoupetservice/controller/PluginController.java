package caigou.caigoupetservice.controller;

import caigou.caigoupetservice.annotation.PublicEndpoint;
import caigou.caigoupetservice.dto.PluginView;
import caigou.caigoupetservice.exception.ApiException;
import caigou.caigoupetservice.service.JwtService;
import caigou.caigoupetservice.service.PluginService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 插件控制器:列表/分类/详情(公开只读)与我的插件(需登录)
 * 本层仅做参数接收与结果返回,业务在 service 层
 * 认证说明:@PublicEndpoint 放行后拦截器不写 currentUserId,详情接口手动解析 Authorization 头计算 isFavorited
 */
@RestController
@RequestMapping("/api/plugins")
@RequiredArgsConstructor
public class PluginController {

    private final PluginService pluginService;
    private final JwtService jwtService;

    /** 插件列表(公开):分页+排序+分类/搜索过滤 */
    @PublicEndpoint
    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int limit,
                                    @RequestParam(defaultValue = "download_count") String sort,
                                    @RequestParam(defaultValue = "DESC") String order,
                                    @RequestParam(required = false) String category,
                                    @RequestParam(required = false) String search) {
        // 非法 sort/order/category 的兜底逻辑在 service 层白名单处理
        return pluginService.list(page, limit, sort, order, category, search);
    }

    /** 可用分类列表(公开) */
    @PublicEndpoint
    @GetMapping("/categories")
    public Map<String, List<String>> categories() {
        return Map.of("categories", pluginService.categories());
    }

    /** 我的插件(需登录,拦截器写入 currentUserId) */
    @GetMapping("/my")
    public Map<String, List<PluginView>> my(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("currentUserId");
        return Map.of("plugins", pluginService.listMy(userId));
    }

    /**
     * 插件详情(公开):404「Plugin not found」,响应含 isFavorited
     * @PublicEndpoint 放行后拦截器不写 currentUserId,这里手动解析 Authorization 头:
     * 有效 token 附 userId 计算收藏态,无头/无效 token 按未收藏处理(不阻断公开访问)
     */
    @PublicEndpoint
    @GetMapping("/{id}")
    public Map<String, PluginView> detail(@PathVariable Long id,
                                          @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long userId = resolveOptionalUserId(authorization);
        return Map.of("plugin", pluginService.detail(id, userId));
    }

    /**
     * 从 Authorization 头解析可选 userId:无头/Bearer 前缀缺失/无效 token 一律返回 null(视为未登录)
     */
    private Long resolveOptionalUserId(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        try {
            return jwtService.parseUserId(authorization.substring("Bearer ".length()));
        } catch (ApiException e) {
            // 无效/过期 token 按未收藏处理,详情仍正常返回
            return null;
        }
    }
}
