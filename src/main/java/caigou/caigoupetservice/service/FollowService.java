package caigou.caigoupetservice.service;

import caigou.caigoupetservice.dto.UserView;
import caigou.caigoupetservice.entity.Follow;
import caigou.caigoupetservice.entity.User;
import caigou.caigoupetservice.exception.ApiException;
import caigou.caigoupetservice.mapper.FollowMapper;
import caigou.caigoupetservice.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 关注业务:幂等关注/取关/粉丝与关注列表,维护双方计数
 */
@Service
@RequiredArgsConstructor
public class FollowService {

    private final FollowMapper followMapper;
    private final UserMapper userMapper;

    /** 关注:关注自己 400;目标不存在或禁用 404;已关注幂等返回 created=false */
    public Map<String, Object> follow(Long userId, Long targetId) {
        // 契约:不能关注自己
        if (targetId.equals(userId)) {
            throw new ApiException(400, "不能关注自己");
        }
        User target = userMapper.findById(targetId);
        // 目标不存在或非正常状态(status≠1)一律 404
        if (target == null || target.getStatus() == null || target.getStatus() != 1) {
            throw new ApiException(404, "用户不存在");
        }
        Follow existing = followMapper.find(targetId, userId);
        // 幂等:已关注直接返回 created=false,不重复计数
        if (existing != null) {
            return Map.of("follow", existing, "created", false);
        }
        Follow follow = new Follow();
        follow.setUserId(targetId);
        follow.setFollowerId(userId);
        followMapper.insert(follow);
        // 仅新建时计数:关注者 following_count +1、被关注者 followers_count +1
        userMapper.changeFollowingCount(userId, 1);
        userMapper.changeFollowersCount(targetId, 1);
        return Map.of("follow", follow, "created", true);
    }

    /** 取消关注:未关注 404;成功后双方计数 -1 */
    public Map<String, String> unfollow(Long userId, Long targetId) {
        // 幂等兜底:无关注记录返回 404
        if (followMapper.find(targetId, userId) == null) {
            throw new ApiException(404, "未关注此用户");
        }
        followMapper.delete(targetId, userId);
        userMapper.changeFollowingCount(userId, -1);
        userMapper.changeFollowersCount(targetId, -1);
        return Map.of("message", "取消关注成功");
    }

    /** 粉丝列表:每条记录内嵌 follower 用户视图 */
    public Map<String, Object> listFollowers(Long userId, int page, int limit) {
        List<Follow> rows = followMapper.listFollowers(userId, (page - 1) * limit, limit);
        // 每条关注记录展开为 {id, created_at, follower},follower 为关注者安全视图
        List<Map<String, Object>> list = rows.stream().map(f -> {
            User follower = userMapper.findById(f.getFollowerId());
            return Map.<String, Object>of(
                    "id", f.getId(), "created_at", f.getCreatedAt(),
                    "follower", follower == null ? null : UserView.from(follower));
        }).toList();
        return Map.of("followers", list, "total", followMapper.countFollowers(userId), "page", page);
    }

    /** 关注列表:每条记录内嵌 following 用户视图 */
    public Map<String, Object> listFollowing(Long userId, int page, int limit) {
        List<Follow> rows = followMapper.listFollowing(userId, (page - 1) * limit, limit);
        // 每条关注记录展开为 {id, created_at, following},following 为被关注者安全视图
        List<Map<String, Object>> list = rows.stream().map(f -> {
            User target = userMapper.findById(f.getUserId());
            return Map.<String, Object>of(
                    "id", f.getId(), "created_at", f.getCreatedAt(),
                    "following", target == null ? null : UserView.from(target));
        }).toList();
        return Map.of("following", list, "total", followMapper.countFollowing(userId), "page", page);
    }
}
