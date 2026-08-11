package caigou.caigoupetservice.service;

import caigou.caigoupetservice.dto.PageView;
import caigou.caigoupetservice.dto.PostView;
import caigou.caigoupetservice.entity.Favorite;
import caigou.caigoupetservice.exception.ApiException;
import caigou.caigoupetservice.mapper.FavoriteMapper;
import caigou.caigoupetservice.mapper.PostMapper;
import caigou.caigoupetservice.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 收藏业务:幂等收藏/取消收藏/收藏帖子列表,维护本人收藏计数
 */
@Service
@RequiredArgsConstructor
public class FavoriteService {

    private final FavoriteMapper favoriteMapper;
    private final PostMapper postMapper;
    private final UserMapper userMapper;
    private final PostService postService;

    /** 收藏:帖不存在 404;已收藏幂等返回 created=false;新建时本人收藏数 +1 */
    public Map<String, Object> favorite(Long userId, Long postId) {
        // 契约:目标帖子必须存在且可见(status=1),否则 404
        if (postMapper.selectVisibleById(postId) == null) {
            throw new ApiException(404, "帖子不存在");
        }
        Favorite existing = favoriteMapper.find(userId, postId);
        // 幂等:已收藏直接返回 created=false,不重复计数
        if (existing != null) {
            return Map.of("favorite", existing, "created", false);
        }
        Favorite favorite = new Favorite();
        favorite.setUserId(userId);
        favorite.setPostId(postId);
        favoriteMapper.insert(favorite);
        // 仅新建时计数:本人收藏数 +1(非作者)
        userMapper.changeFavoritesCount(userId, 1);
        return Map.of("favorite", favorite, "created", true);
    }

    /** 取消收藏:未收藏 404;成功后本人收藏数 -1 */
    public Map<String, String> unfavorite(Long userId, Long postId) {
        // 幂等兜底:无收藏记录返回 404
        if (favoriteMapper.find(userId, postId) == null) {
            throw new ApiException(404, "未收藏");
        }
        favoriteMapper.delete(userId, postId);
        userMapper.changeFavoritesCount(userId, -1);
        return Map.of("message", "取消收藏成功");
    }

    /** 用户收藏过的帖子列表(分页) */
    public PageView<PostView> listUserPosts(Long userId, int page, int limit) {
        // 与点赞列表同构:JOIN 反查可见帖子,再按 id 组装作者视图
        List<PostView> rows = favoriteMapper.listUserPosts(userId, (page - 1) * limit, limit)
                .stream().map(p -> postService.postViewById(p.getId()))
                .filter(java.util.Objects::nonNull).toList();
        return new PageView<>(rows, favoriteMapper.countUserPosts(userId), page);
    }
}
