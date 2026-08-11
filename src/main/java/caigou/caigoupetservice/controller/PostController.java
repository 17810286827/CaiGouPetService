package caigou.caigoupetservice.controller;

import caigou.caigoupetservice.annotation.PublicEndpoint;
import caigou.caigoupetservice.dto.PageView;
import caigou.caigoupetservice.dto.PostCreateRequest;
import caigou.caigoupetservice.dto.PostView;
import caigou.caigoupetservice.service.PostService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 帖子控制器:创建/列表/详情/编辑/删除
 * 本层仅做参数接收与返回组装,业务在 service 层
 * 认证说明:list/detail 公开只读加 @PublicEndpoint;创建/编辑/删除需登录(拦截器校验,不加注解)
 */
@RestController
@RequestMapping("/api/posts")
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;

    /** 创建帖子:成功 201 */
    @PostMapping
    public ResponseEntity<Map<String, PostView>> create(@RequestBody PostCreateRequest req,
                                                        HttpServletRequest request) {
        // 当前登录用户ID由 JWT 拦截器写入 request attribute,作者取它而非请求体
        Long userId = (Long) request.getAttribute("currentUserId");
        return ResponseEntity.status(201).body(Map.of("post", postService.create(userId, req)));
    }

    /** 首页流(公开) */
    @PublicEndpoint
    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int limit) {
        PageView<PostView> view = postService.feed(page, limit);
        // 返回结构与 Express 一致:{posts, total, page},不额外回显 limit
        return Map.of("posts", view.getRows(), "total", view.getTotal(), "page", view.getPage());
    }

    /** 详情(公开):成功后 service 内部自增浏览量 */
    @PublicEndpoint
    @GetMapping("/{id}")
    public Map<String, PostView> detail(@PathVariable Long id) {
        // 404(不存在/已软删)由 service 抛 ApiException,全局处理器统一转 {error}
        return Map.of("post", postService.detail(id));
    }

    /** 编辑(作者):越权由 service 抛 403 */
    @PutMapping("/{id}")
    public Map<String, PostView> update(@PathVariable Long id, @RequestBody PostCreateRequest req,
                                        HttpServletRequest request) {
        // 当前登录者与帖子作者是否一致由 service 校验
        Long userId = (Long) request.getAttribute("currentUserId");
        return Map.of("post", postService.update(id, userId, req));
    }

    /** 删除(作者):软删除,越权由 service 抛 403 */
    @DeleteMapping("/{id}")
    public Map<String, String> delete(@PathVariable Long id, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("currentUserId");
        postService.delete(id, userId);
        return Map.of("message", "删除成功");
    }
}
