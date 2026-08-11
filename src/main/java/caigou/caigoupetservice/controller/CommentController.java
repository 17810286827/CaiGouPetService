package caigou.caigoupetservice.controller;

import caigou.caigoupetservice.annotation.PublicEndpoint;
import caigou.caigoupetservice.dto.CommentRequest;
import caigou.caigoupetservice.dto.CommentView;
import caigou.caigoupetservice.dto.PageView;
import caigou.caigoupetservice.service.CommentService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 评论控制器:发评论/帖子评论树/删除评论
 * 本层仅做参数接收与返回组装,业务校验与异常抛出都在 service 层
 * 认证说明:GET 评论树为公开只读接口加 @PublicEndpoint;POST/DELETE 需登录(拦截器校验,不加注解)
 */
@RestController
@RequestMapping("/api/comments")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;

    /**
     * 发表评论(一级或二级):成功 201
     * 参数校验(必填/帖子存在/父评论存在)与 root_id 计算在 service 层完成
     */
    @PostMapping
    public ResponseEntity<Map<String, CommentView>> create(@RequestBody CommentRequest req,
                                                           HttpServletRequest request) {
        // 当前登录用户ID由 JWT 拦截器写入 request attribute,评论者取它而非请求体
        Long userId = (Long) request.getAttribute("currentUserId");
        return ResponseEntity.status(201).body(Map.of("comment", commentService.create(userId, req)));
    }

    /**
     * 帖子评论树(公开):根评论时间倒序 + 子评论时间正序,total 为根评论数
     * 分页参数默认与 Express 一致:page=1、limit=20
     */
    @PublicEndpoint
    @GetMapping("/post/{postId}")
    public Map<String, Object> list(@PathVariable Long postId,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int limit) {
        // 返回结构与 Express 一致:{comments, total, page},不额外回显 limit
        PageView<CommentView> view = commentService.listByPost(postId, page, limit);
        return Map.of("comments", view.getRows(), "total", view.getTotal(), "page", view.getPage());
    }

    /**
     * 删除评论(作者):软删除,越权由 service 抛 403
     * 评论不存在返回 404 的判断同样在 service 层
     */
    @DeleteMapping("/{id}")
    public Map<String, String> delete(@PathVariable Long id, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("currentUserId");
        commentService.delete(id, userId);
        return Map.of("message", "删除成功");
    }
}
