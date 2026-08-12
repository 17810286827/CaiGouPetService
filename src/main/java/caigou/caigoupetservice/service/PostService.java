package caigou.caigoupetservice.service;

import caigou.caigoupetservice.dto.PageView;
import caigou.caigoupetservice.dto.PostCreateRequest;
import caigou.caigoupetservice.dto.PostView;
import caigou.caigoupetservice.dto.UserView;
import caigou.caigoupetservice.entity.Post;
import caigou.caigoupetservice.entity.User;
import caigou.caigoupetservice.exception.ApiException;
import caigou.caigoupetservice.mapper.PostMapper;
import caigou.caigoupetservice.mapper.UserMapper;
import caigou.caigoupetservice.util.Pagination;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 帖子业务:创建/首页流/详情(浏览量自增)/编辑/软删除/用户帖子
 * 业务异常统一抛 ApiException(status, 中文信息),由全局异常处理器转换为 {error:"..."}
 * 作者信息用 UserMapper.findById 逐条组装(数据量小,简单可靠,不筛 status)
 */
@Service
@RequiredArgsConstructor
public class PostService {

    /** JSON 读写工具:标签数组与字符串互转 */
    private static final ObjectMapper OM = new ObjectMapper();

    private final PostMapper postMapper;
    private final UserMapper userMapper;

    /** 创建帖子:正文必填,状态固定公开 */
    public PostView create(Long userId, PostCreateRequest req) {
        // 契约:content 为空(含空白)直接 400,不落库
        if (req.getContent() == null || req.getContent().isBlank()) {
            throw new ApiException(400, "内容不能为空");
        }
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
        post.setStatus(1);
        postMapper.insert(post);
        return PostView.from(post, authorView(userId));
    }

    /** 首页帖子流:置顶优先、时间倒序 */
    public PageView<PostView> feed(int page, int limit) {
        // 分页钳制:page 最小 1、limit 上限 100,对齐 Express,避免负 offset 触发 SQL 500
        page = Pagination.clampPage(page);
        limit = Pagination.clampLimit(limit);
        // 每页数据量小,逐条查询作者组装视图即可,避免 JOIN 复杂化
        List<PostView> rows = postMapper.listFeed((page - 1) * limit, limit)
                .stream().map(p -> PostView.from(p, authorView(p.getUserId()))).toList();
        return new PageView<>(rows, postMapper.countFeed(), page);
    }

    /** 帖子详情:不存在返回 404,成功后浏览量自增 */
    public PostView detail(Long id) {
        Post post = postMapper.selectVisibleById(id);
        if (post == null) {
            throw new ApiException(404, "帖子不存在");
        }
        // 契约:查询成功即浏览量 +1;对内存快照同步自增,响应返回自增后的值(对齐 Express 的 post.increment)
        postMapper.incrementView(id);
        post.setViewCount(post.getViewCount() + 1);
        return PostView.from(post, authorView(post.getUserId()));
    }

    /** 编辑帖子:仅作者可编辑 */
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
        Post upd = new Post();
        upd.setId(id);
        upd.setTitle(req.getTitle());
        upd.setContent(req.getContent());
        upd.setContentType(req.getContentType());
        upd.setSummary(req.getSummary());
        upd.setCoverUrl(req.getCoverUrl());
        upd.setTags(req.getTags() == null ? null : toTagsJson(req.getTags()));
        postMapper.update(upd);
        // 编辑不虚增浏览量:回查最新帖子直接构造视图(不调 detail,避免触发 incrementView)
        // selectById 不筛状态:上方已校验帖子存在且非软删,回查结果必非空
        Post updated = postMapper.selectById(id);
        return PostView.from(updated, authorView(updated.getUserId()));
    }

    /** 软删除帖子:仅作者可删 */
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

    /** 用户公开帖子列表 */
    public PageView<PostView> userPosts(Long userId, int page, int limit) {
        // 分页钳制:page 最小 1、limit 上限 100,对齐 Express,避免负 offset 触发 SQL 500
        page = Pagination.clampPage(page);
        limit = Pagination.clampLimit(limit);
        // 与首页流相同的作者组装方式:findById 不筛 status,禁用作者帖子仍可见
        List<PostView> rows = postMapper.listByUser(userId, (page - 1) * limit, limit)
                .stream().map(p -> PostView.from(p, authorView(p.getUserId()))).toList();
        return new PageView<>(rows, postMapper.countByUser(userId), page);
    }

    /** 按 id 查询可见帖子的视图(供点赞/收藏列表用,不存在返回 null) */
    public PostView postViewById(Long id) {
        Post post = postMapper.selectVisibleById(id);
        return post == null ? null : PostView.from(post, authorView(post.getUserId()));
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
