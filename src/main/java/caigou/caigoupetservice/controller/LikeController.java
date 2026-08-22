package caigou.caigoupetservice.controller;

import caigou.caigoupetservice.annotation.PublicEndpoint;
import caigou.caigoupetservice.dto.PageView;
import caigou.caigoupetservice.dto.PostView;
import caigou.caigoupetservice.service.LikeService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 点赞控制器:点赞(幂等)/取消点赞/点赞帖子列表
 * POST/DELETE 需登录(拦截器校验),GET 列表公开只读加 @PublicEndpoint
 */
@RestController
@RequestMapping("/api/likes")
@RequiredArgsConstructor
public class LikeController {

    private final LikeService likeService;

    /** 点赞:新建 201,已存在 200(created=false) */
    @PostMapping("/{postId}")
    public ResponseEntity<Map<String, Object>> like(@PathVariable Long postId, HttpServletRequest request) {
        // 当前登录用户ID由 JWT 拦截器写入 request attribute
        Long userId = (Long) request.getAttribute("currentUserId");
        Map<String, Object> result = likeService.like(userId, postId);
        boolean created = (boolean) result.get("created");
        return ResponseEntity.status(created ? 201 : 200).body(result);
    }

    /** 取消点赞 */
    @DeleteMapping("/{postId}")
    public Map<String, String> unlike(@PathVariable Long postId, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("currentUserId");
        return likeService.unlike(userId, postId);
    }

    /** 用户点赞过的帖子列表(公开) */
    @PublicEndpoint
    @GetMapping("/user/{userId}")
    public Map<String, Object> list(@PathVariable Long userId,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int limit,
                                    HttpServletRequest request) {
        // 公开端点可选登录:当前登录用户作为 viewerId,用于帖子可见性过滤
        Long viewerId = (Long) request.getAttribute("currentUserId");
        // 返回结构与 Express 一致:{posts, total, page}
        PageView<PostView> view = likeService.listUserPosts(userId, page, limit, viewerId);
        return Map.of("posts", view.getRows(), "total", view.getTotal(), "page", view.getPage());
    }
}
