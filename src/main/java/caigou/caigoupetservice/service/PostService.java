package caigou.caigoupetservice.service;

import caigou.caigoupetservice.dto.PageView;
import caigou.caigoupetservice.dto.PostCreateRequest;
import caigou.caigoupetservice.dto.PostView;
import caigou.caigoupetservice.dto.UserView;
import caigou.caigoupetservice.entity.Post;
import caigou.caigoupetservice.entity.User;
import caigou.caigoupetservice.exception.ApiException;
import caigou.caigoupetservice.mapper.FollowMapper;
import caigou.caigoupetservice.mapper.PostMapper;
import caigou.caigoupetservice.mapper.UserMapper;
import caigou.caigoupetservice.util.Pagination;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 帖子业务:创建/首页流/详情(浏览量自增)/编辑/软删除/用户帖子/草稿
 * 业务异常统一抛 ApiException(status, 中文信息),由全局异常处理器转换为 {error:"..."}
 * 可见性过滤:草稿仅作者可见,公开读接口按 viewerId 与关注关系过滤四档可见性
 */
@Service
@RequiredArgsConstructor
public class PostService {

    /** JSON 读写工具:标签数组与字符串互转 */
    private static final ObjectMapper OM = new ObjectMapper();
    /** 标题最大长度 */
    private static final int MAX_TITLE = 50;
    /** 话题最大个数 */
    private static final int MAX_TAGS = 3;
    /** 单个话题最大长度 */
    private static final int MAX_TAG_LEN = 20;
    /** 图片最大张数 */
    private static final int MAX_IMAGES = 4;

    private final PostMapper postMapper;
    private final UserMapper userMapper;
    private final FollowMapper followMapper;

    /** 创建帖子/草稿:发布正文必填;草稿至少一项内容 */
    public PostView create(Long userId, PostCreateRequest req) {
        validatePost(req);
        Post post = new Post();
        // 作者取当前登录用户,不信任请求体里的用户字段
        post.setUserId(userId);
        post.setTitle(req.getTitle());
        post.setContent(req.getContent());
        post.setContentType(req.getContentType() == null ? 0 : req.getContentType());
        // 摘要/封面/标签均可空,未传时存 NULL/空数组
        post.setSummary(req.getSummary());
        post.setCoverUrl(req.getCoverUrl());
        post.setTags(toTagsJson(req.getTags()));
        post.setStatus(req.getStatus() == null ? 1 : req.getStatus());
        post.setVisibility(req.getVisibility() == null ? 1 : req.getVisibility());
        postMapper.insert(post);
        return PostView.from(post, authorView(userId));
    }

    /** 首页帖子流:按可见性过滤后组装视图 */
    public PageView<PostView> feed(int page, int limit, Long viewerId) {
        // 分页钳制:page 最小 1、limit 上限 100,对齐 Express,避免负 offset 触发 SQL 500
        page = Pagination.clampPage(page);
        limit = Pagination.clampLimit(limit);
        // 每页数据量小,逐条查询作者组装视图即可,避免 JOIN 复杂化
        List<PostView> rows = postMapper.listFeed((page - 1) * limit, limit)
                .stream().filter(p -> canView(p, viewerId))
                .map(p -> PostView.from(p, authorView(p.getUserId()))).toList();
        return new PageView<>(rows, postMapper.countFeed(), page);
    }

    /** 帖子详情:不可见按 404 处理 */
    public PostView detail(Long id, Long viewerId) {
        Post post = postMapper.selectVisibleById(id);
        if (post == null || !canView(post, viewerId)) {
            throw new ApiException(404, "帖子不存在");
        }
        // 契约:查询成功即浏览量 +1;对内存快照同步自增,响应返回自增后的值(对齐 Express 的 post.increment)
        postMapper.incrementView(id);
        post.setViewCount(post.getViewCount() + 1);
        return PostView.from(post, authorView(post.getUserId()));
    }

    /** 编辑帖子/草稿:仅作者;校验规则同创建 */
    public PostView update(Long id, Long userId, PostCreateRequest req) {
        // 越权判断用 selectById(不筛状态):软删帖子视为不存在
        Post post = postMapper.selectById(id);
        if (post == null || post.getStatus() == 2) {
            throw new ApiException(404, "帖子不存在");
        }
        // 仅作者本人可改,他人一律 403
        if (!post.getUserId().equals(userId)) {
            throw new ApiException(403, "无权编辑此帖子");
        }
        validatePost(req);
        Post upd = new Post();
        upd.setId(id);
        upd.setTitle(req.getTitle());
        upd.setContent(req.getContent());
        upd.setContentType(req.getContentType());
        upd.setSummary(req.getSummary());
        upd.setCoverUrl(req.getCoverUrl());
        upd.setTags(req.getTags() == null ? null : toTagsJson(req.getTags()));
        upd.setStatus(req.getStatus());
        upd.setVisibility(req.getVisibility());
        postMapper.update(upd);
        // 编辑不虚增浏览量:回查最新帖子直接构造视图(不调 detail,避免触发 incrementView)
        // selectById 不筛状态:上方已校验帖子存在且非软删,回查结果必非空
        Post updated = postMapper.selectById(id);
        return PostView.from(updated, authorView(updated.getUserId()));
    }

    /** 软删除帖子/草稿:仅作者 */
    public void delete(Long id, Long userId) {
        Post post = postMapper.selectById(id);
        if (post == null || post.getStatus() == 2) {
            throw new ApiException(404, "帖子不存在");
        }
        if (!post.getUserId().equals(userId)) {
            throw new ApiException(403, "无权删除此帖子");
        }
        // 软删除:仅置 status=2 并记录删除时间,正文保留以便审计与恢复
        postMapper.softDelete(id);
    }

    /** 用户公开帖子列表:按 viewerId 过滤可见性 */
    public PageView<PostView> userPosts(Long userId, int page, int limit, Long viewerId) {
        // 分页钳制:page 最小 1、limit 上限 100,对齐 Express,避免负 offset 触发 SQL 500
        page = Pagination.clampPage(page);
        limit = Pagination.clampLimit(limit);
        // 与首页流相同的作者组装方式:findById 不筛 status,禁用作者帖子仍可见
        List<PostView> rows = postMapper.listByUser(userId, (page - 1) * limit, limit)
                .stream().filter(p -> canView(p, viewerId))
                .map(p -> PostView.from(p, authorView(p.getUserId()))).toList();
        return new PageView<>(rows, postMapper.countByUser(userId), page);
    }

    /** 本人草稿列表(草稿仅作者可见,不做可见性过滤) */
    public PageView<PostView> drafts(Long userId, int page, int limit) {
        // 分页钳制:page 最小 1、limit 上限 100,对齐 Express,避免负 offset 触发 SQL 500
        page = Pagination.clampPage(page);
        limit = Pagination.clampLimit(limit);
        List<PostView> rows = postMapper.listDraftsByUser(userId, (page - 1) * limit, limit)
                .stream().map(p -> PostView.from(p, authorView(p.getUserId()))).toList();
        return new PageView<>(rows, postMapper.countDraftsByUser(userId), page);
    }

    /** 按 id 查询可见帖子视图(供点赞/收藏列表用,不存在返回 null) */
    public PostView postViewById(Long id, Long viewerId) {
        Post post = postMapper.selectVisibleById(id);
        if (post == null || !canView(post, viewerId)) {
            return null;
        }
        return PostView.from(post, authorView(post.getUserId()));
    }

    /** 可见性校验:公开所有人;仅粉丝/仅好友/仅自己按关注关系 */
    public boolean canView(Post post, Long viewerId) {
        // 历史数据无 visibility 字段时按公开处理
        Integer visibility = post.getVisibility() == null ? 1 : post.getVisibility();
        if (visibility == 1) return true;
        // 未登录只能看到公开帖子
        if (viewerId == null) return false;
        // 作者本人始终可见
        if (post.getUserId().equals(viewerId)) return true;
        // 仅粉丝:viewer 关注了作者即可见
        if (visibility == 2) return followMapper.isFollowing(viewerId, post.getUserId());
        // 仅好友:双方互相关注才可见
        if (visibility == 3) return followMapper.isMutual(viewerId, post.getUserId());
        // 仅自己:作者已覆盖,其余一律不可见
        return false;
    }

    /** 创建/编辑校验:状态范围、可见性范围、发布必填正文、草稿至少一项 */
    private void validatePost(PostCreateRequest req) {
        int status = req.getStatus() == null ? 1 : req.getStatus();
        int visibility = req.getVisibility() == null ? 1 : req.getVisibility();
        if (status != 0 && status != 1) {
            throw new ApiException(400, "status 只能是 0 或 1");
        }
        if (visibility < 1 || visibility > 4) {
            throw new ApiException(400, "visibility 只能是 1-4");
        }
        if (req.getTitle() != null && req.getTitle().length() > MAX_TITLE) {
            throw new ApiException(400, "标题不能超过 50 字");
        }
        List<String> tags = req.getTags() == null ? List.of() : req.getTags();
        if (tags.size() > MAX_TAGS) {
            throw new ApiException(400, "话题不能超过 3 个");
        }
        for (String tag : tags) {
            if (tag != null && tag.length() > MAX_TAG_LEN) {
                throw new ApiException(400, "单个话题不能超过 20 字");
            }
        }
        if (req.getCoverUrl() != null && req.getCoverUrl().split(",").length > MAX_IMAGES) {
            throw new ApiException(400, "图片不能超过 4 张");
        }
        boolean hasContent = hasAnyContent(req);
        // 草稿:标题/正文/图片/话题至少一项,否则 400
        if (status == 0 && !hasContent) {
            throw new ApiException(400, "草稿内容不能为空");
        }
        // 发布:正文必填(与旧契约一致)
        if (status == 1 && (req.getContent() == null || req.getContent().isBlank())) {
            throw new ApiException(400, "内容不能为空");
        }
    }

    /** 草稿"至少一项内容"判断:标题/正文/封面/话题任一非空 */
    private boolean hasAnyContent(PostCreateRequest req) {
        return (req.getTitle() != null && !req.getTitle().isBlank())
                || (req.getContent() != null && !req.getContent().isBlank())
                || (req.getCoverUrl() != null && !req.getCoverUrl().isBlank())
                || (req.getTags() != null && !req.getTags().isEmpty());
    }

    /** 查询作者视图:findById 不筛 status,作者被禁用仍展示其历史帖子 */
    private UserView authorView(Long userId) {
        User u = userMapper.findById(userId);
        // 作者行缺失时返回 null,响应中 user 内嵌为 null(前端自行兜底)
        return u == null ? null : UserView.from(u);
    }

    /** 标签数组序列化为 JSON 字符串(空/序列化失败回退 []) */
    private String toTagsJson(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return "[]";
        }
        try {
            return OM.writeValueAsString(tags);
        } catch (Exception e) {
            return "[]";
        }
    }
}
