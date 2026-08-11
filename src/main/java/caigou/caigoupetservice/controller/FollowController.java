package caigou.caigoupetservice.controller;

import caigou.caigoupetservice.annotation.PublicEndpoint;
import caigou.caigoupetservice.service.FollowService;
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
 * 关注控制器:关注(幂等)/取消关注/粉丝与关注列表
 * POST/DELETE 需登录,GET 粉丝/关注列表公开只读加 @PublicEndpoint
 */
@RestController
@RequestMapping("/api/follow")
@RequiredArgsConstructor
public class FollowController {

    private final FollowService followService;

    /** 关注:新建 201,已存在 200(created=false) */
    @PostMapping("/{userId}")
    public ResponseEntity<Map<String, Object>> follow(@PathVariable Long userId, HttpServletRequest request) {
        Long currentUserId = (Long) request.getAttribute("currentUserId");
        Map<String, Object> result = followService.follow(currentUserId, userId);
        boolean created = (boolean) result.get("created");
        return ResponseEntity.status(created ? 201 : 200).body(result);
    }

    /** 取消关注 */
    @DeleteMapping("/{userId}")
    public Map<String, String> unfollow(@PathVariable Long userId, HttpServletRequest request) {
        Long currentUserId = (Long) request.getAttribute("currentUserId");
        return followService.unfollow(currentUserId, userId);
    }

    /** 粉丝列表(公开) */
    @PublicEndpoint
    @GetMapping("/{userId}/followers")
    public Map<String, Object> followers(@PathVariable Long userId,
                                         @RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int limit) {
        // 返回 {followers, total, page}
        return followService.listFollowers(userId, page, limit);
    }

    /** 关注列表(公开) */
    @PublicEndpoint
    @GetMapping("/{userId}/following")
    public Map<String, Object> following(@PathVariable Long userId,
                                         @RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int limit) {
        // 返回 {following, total, page}
        return followService.listFollowing(userId, page, limit);
    }
}
