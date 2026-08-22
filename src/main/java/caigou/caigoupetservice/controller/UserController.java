package caigou.caigoupetservice.controller;

import caigou.caigoupetservice.annotation.PublicEndpoint;
import caigou.caigoupetservice.dto.PageView;
import caigou.caigoupetservice.dto.PostView;
import caigou.caigoupetservice.dto.ProfileUpdateRequest;
import caigou.caigoupetservice.dto.UserSearchView;
import caigou.caigoupetservice.service.PostService;
import caigou.caigoupetservice.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 用户控制器:搜索/详情/资料更新
 * 本层仅做参数接收与结果组装,业务逻辑全部在 UserService
 * 认证说明:search/updateProfile 需登录(JWT 拦截器校验,不加 @PublicEndpoint);
 * getById 为公开只读接口,加 @PublicEndpoint 放行
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final PostService postService;

    /**
     * 搜索用户(需登录):按关键字匹配用户名/昵称,排除当前登录者自己
     * @param q 搜索关键字,可为空(空串时 service 返回空列表)
     */
    @GetMapping("/search")
    public Map<String, List<UserSearchView>> search(@RequestParam(required = false) String q,
                                                    HttpServletRequest request) {
        // 当前登录用户ID由 JWT 拦截器写入 request attribute
        Long selfId = (Long) request.getAttribute("currentUserId");
        return Map.of("users", userService.search(q, selfId));
    }

    /**
     * 用户详情(公开):任意用户可访问,目标不存在返回 404 "用户不存在"
     */
    @PublicEndpoint
    @GetMapping("/{id}")
    public Map<String, Object> getById(@PathVariable Long id) {
        return Map.of("user", userService.getProfile(id));
    }

    /**
     * 用户公开帖子列表(公开):分页返回该用户 status=1 的帖子,按创建时间倒序
     * 复用 PostService.userPosts,不重复实现业务逻辑
     */
    @PublicEndpoint
    @GetMapping("/{id}/posts")
    public Map<String, Object> userPosts(@PathVariable Long id,
                                         @RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int limit,
                                         HttpServletRequest request) {
        // 公开端点可选登录:当前登录用户作为 viewerId,用于可见性过滤
        Long viewerId = (Long) request.getAttribute("currentUserId");
        PageView<PostView> view = postService.userPosts(id, page, limit, viewerId);
        return Map.of("posts", view.getRows(), "total", view.getTotal(), "page", view.getPage());
    }

    /**
     * 更新资料(需登录):仅更新传入的非空字段,成功后返回最新用户详情
     */
    @PutMapping("/profile")
    public Map<String, Object> updateProfile(@RequestBody ProfileUpdateRequest req,
                                             HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("currentUserId");
        return Map.of("user", userService.updateProfile(userId, req));
    }
}
