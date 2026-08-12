package caigou.caigoupetservice.service;

import caigou.caigoupetservice.dto.PageView;
import caigou.caigoupetservice.dto.PostView;
import caigou.caigoupetservice.entity.Like;
import caigou.caigoupetservice.exception.ApiException;
import caigou.caigoupetservice.mapper.LikeMapper;
import caigou.caigoupetservice.mapper.PostMapper;
import caigou.caigoupetservice.mapper.UserMapper;
import caigou.caigoupetservice.util.Pagination;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 点赞业务:幂等点赞/取消点赞/点赞帖子列表,维护帖子与作者计数
 */
@Service
@RequiredArgsConstructor
public class LikeService {

    private final LikeMapper likeMapper;
    private final PostMapper postMapper;
    private final UserMapper userMapper;
    private final PostService postService;

    /** 点赞:帖不存在 404;已点赞幂等返回 created=false;新建时帖子点赞数与作者获赞数 +1 */
    public Map<String, Object> like(Long userId, Long postId) {
        // 契约:目标帖子必须存在且可见(status=1),否则 404
        if (postMapper.selectVisibleById(postId) == null) {
            throw new ApiException(404, "帖子不存在");
        }
        Like existing = likeMapper.find(userId, postId);
        // 幂等:已点赞直接返回 created=false,不重复计数
        if (existing != null) {
            return Map.of("like", existing, "created", false);
        }
        Like like = new Like();
        like.setUserId(userId);
        like.setPostId(postId);
        try {
            likeMapper.insert(like);
        } catch (DuplicateKeyException e) {
            // 并发防护:并发下唯一键冲突说明对方已插入,回查后按"已存在"幂等返回 created=false,不重复计数
            Like concurrent = likeMapper.find(userId, postId);
            return Map.of("like", concurrent, "created", false);
        }
        // 仅新建时计数:帖子点赞数 +1、作者获赞数 +1
        Long authorId = postMapper.selectById(postId).getUserId();
        postMapper.changeLikeCount(postId, 1);
        userMapper.changeLikesCount(authorId, 1);
        return Map.of("like", like, "created", true);
    }

    /** 取消点赞:未点赞 404;成功后帖子点赞数与作者获赞数 -1 */
    public Map<String, String> unlike(Long userId, Long postId) {
        // 幂等兜底:无点赞记录返回 404
        if (likeMapper.find(userId, postId) == null) {
            throw new ApiException(404, "未点赞");
        }
        // 并发防护:delete 返回 0 说明记录在 find 与 delete 之间被对方删除,保持 404 语义且不递减计数
        if (likeMapper.delete(userId, postId) == 0) {
            throw new ApiException(404, "未点赞");
        }
        // 计数回退:仅删除成功才递减(selectById 不筛状态,软删帖子仍可取到作者)
        Long authorId = postMapper.selectById(postId).getUserId();
        postMapper.changeLikeCount(postId, -1);
        userMapper.changeLikesCount(authorId, -1);
        return Map.of("message", "取消点赞成功");
    }

    /** 用户点赞过的帖子列表(分页) */
    public PageView<PostView> listUserPosts(Long userId, int page, int limit) {
        // 分页钳制:page 最小 1、limit 上限 100,对齐 Express,避免负 offset 触发 SQL 500
        page = Pagination.clampPage(page);
        limit = Pagination.clampLimit(limit);
        // JOIN 反查可见帖子,再按 id 组装作者视图(过滤掉查询后可能被删除的帖子)
        List<PostView> rows = likeMapper.listUserPosts(userId, (page - 1) * limit, limit)
                .stream().map(p -> postService.postViewById(p.getId()))
                .filter(java.util.Objects::nonNull).toList();
        return new PageView<>(rows, likeMapper.countUserPosts(userId), page);
    }
}
