package caigou.caigoupetservice.service;

import caigou.caigoupetservice.dto.UserView;
import caigou.caigoupetservice.entity.Follow;
import caigou.caigoupetservice.entity.User;
import caigou.caigoupetservice.exception.ApiException;
import caigou.caigoupetservice.mapper.FollowMapper;
import caigou.caigoupetservice.mapper.UserMapper;
import caigou.caigoupetservice.util.Pagination;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
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
        try {
            followMapper.insert(follow);
        } catch (DuplicateKeyException e) {
            // 并发防护:并发下唯一键冲突说明对方已插入,回查后按"已关注"幂等返回 created=false,不重复计数
            Follow concurrent = followMapper.find(targetId, userId);
            return Map.of("follow", concurrent, "created", false);
        }
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
        // 并发防护:delete 返回 0 说明记录在 find 与 delete 之间被对方删除,保持 404 语义且不递减计数
        if (followMapper.delete(targetId, userId) == 0) {
            throw new ApiException(404, "未关注此用户");
        }
        userMapper.changeFollowingCount(userId, -1);
        userMapper.changeFollowersCount(targetId, -1);
        return Map.of("message", "取消关注成功");
    }

    /** 粉丝列表:每条记录内嵌 follower 用户视图 */
    public Map<String, Object> listFollowers(Long userId, int page, int limit) {
        // 分页钳制:page 最小 1、limit 上限 100,对齐 Express,避免负 offset 触发 SQL 500
        page = Pagination.clampPage(page);
        limit = Pagination.clampLimit(limit);
        List<Follow> rows = followMapper.listFollowers(userId, (page - 1) * limit, limit);
        // 每条关注记录展开为 {id, created_at, follower},follower 为关注者安全视图
        List<Map<String, Object>> list = rows.stream().map(f -> {
            User follower = userMapper.findById(f.getFollowerId());
            // 用 LinkedHashMap:follower 可能为 null,Map.of 禁空值会抛 NPE;LinkedHashMap 保留声明顺序且允许 null
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", f.getId());
            item.put("created_at", f.getCreatedAt());
            item.put("follower", follower == null ? null : UserView.from(follower));
            return item;
        }).toList();
        return Map.of("followers", list, "total", followMapper.countFollowers(userId), "page", page);
    }

    /** 关注列表:每条记录内嵌 following 用户视图 */
    public Map<String, Object> listFollowing(Long userId, int page, int limit) {
        // 分页钳制:page 最小 1、limit 上限 100,对齐 Express,避免负 offset 触发 SQL 500
        page = Pagination.clampPage(page);
        limit = Pagination.clampLimit(limit);
        List<Follow> rows = followMapper.listFollowing(userId, (page - 1) * limit, limit);
        // 每条关注记录展开为 {id, created_at, following},following 为被关注者安全视图
        List<Map<String, Object>> list = rows.stream().map(f -> {
            User target = userMapper.findById(f.getUserId());
            // 用 LinkedHashMap:following 可能为 null,Map.of 禁空值会抛 NPE;LinkedHashMap 保留声明顺序且允许 null
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", f.getId());
            item.put("created_at", f.getCreatedAt());
            item.put("following", target == null ? null : UserView.from(target));
            return item;
        }).toList();
        return Map.of("following", list, "total", followMapper.countFollowing(userId), "page", page);
    }
}
