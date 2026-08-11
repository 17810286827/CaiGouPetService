package caigou.caigoupetservice.service;

import caigou.caigoupetservice.dto.PageView;
import caigou.caigoupetservice.dto.PostView;
import caigou.caigoupetservice.entity.Like;
import caigou.caigoupetservice.exception.ApiException;
import caigou.caigoupetservice.mapper.LikeMapper;
import caigou.caigoupetservice.mapper.PostMapper;
import caigou.caigoupetservice.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
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
        likeMapper.insert(like);
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
        likeMapper.delete(userId, postId);
        // 计数回退:帖子点赞数 -1、作者获赞数 -1(selectById 不筛状态,软删帖子仍可取到作者)
        Long authorId = postMapper.selectById(postId).getUserId();
        postMapper.changeLikeCount(postId, -1);
        userMapper.changeLikesCount(authorId, -1);
        return Map.of("message", "取消点赞成功");
    }

    /** 用户点赞过的帖子列表(分页) */
    public PageView<PostView> listUserPosts(Long userId, int page, int limit) {
        // JOIN 反查可见帖子,再按 id 组装作者视图(过滤掉查询后可能被删除的帖子)
        List<PostView> rows = likeMapper.listUserPosts(userId, (page - 1) * limit, limit)
                .stream().map(p -> postService.postViewById(p.getId()))
                .filter(java.util.Objects::nonNull).toList();
        return new PageView<>(rows, likeMapper.countUserPosts(userId), page);
    }
}
