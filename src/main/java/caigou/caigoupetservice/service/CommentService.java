package caigou.caigoupetservice.service;

import caigou.caigoupetservice.dto.CommentRequest;
import caigou.caigoupetservice.dto.CommentView;
import caigou.caigoupetservice.dto.PageView;
import caigou.caigoupetservice.dto.UserView;
import caigou.caigoupetservice.entity.Comment;
import caigou.caigoupetservice.entity.User;
import caigou.caigoupetservice.exception.ApiException;
import caigou.caigoupetservice.mapper.CommentMapper;
import caigou.caigoupetservice.mapper.PostMapper;
import caigou.caigoupetservice.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 评论业务:发根评论/发回复/帖子评论树/软删除
 * 两级树形:一级评论挂 replies,二级回复归属到其根评论
 * 业务异常统一抛 ApiException(status, 中文信息),由全局异常处理器转换为 {error:"..."}
 * 作者信息用 UserMapper.findById 逐条组装(数据量小,简单可靠)
 */
@Service
@RequiredArgsConstructor
public class CommentService {

    private final CommentMapper commentMapper;
    private final PostMapper postMapper;
    private final UserMapper userMapper;

    /**
     * 发表评论(一级或二级):校验必填与帖子存在,维护帖子评论计数 +1
     */
    public CommentView create(Long userId, CommentRequest req) {
        // 契约:post_id 与 content 均必填,任一为空(含空白)直接 400
        if (req.getPostId() == null || req.getContent() == null || req.getContent().isBlank()) {
            throw new ApiException(400, "post_id 和 content 不能为空");
        }
        // 目标帖子必须存在且可见(status=1),否则 404
        if (postMapper.selectVisibleById(req.getPostId()) == null) {
            throw new ApiException(404, "帖子不存在");
        }
        Comment comment = new Comment();
        comment.setPostId(req.getPostId());
        comment.setUserId(userId);
        comment.setContent(req.getContent());
        // 二级回复:父评论必须存在,root_id 沿用父评论的根;一级评论 parent/root 均为空
        if (req.getParentId() != null) {
            Comment parent = commentMapper.selectById(req.getParentId());
            if (parent == null) {
                throw new ApiException(404, "父评论不存在");
            }
            comment.setParentId(parent.getId());
            comment.setRootId(parent.getRootId() == null ? parent.getId() : parent.getRootId());
        }
        commentMapper.insert(comment);
        // 计数维护:帖子评论数 +1
        postMapper.changeCommentCount(req.getPostId(), 1);
        // 新建评论无回复,replies 传空列表
        return CommentView.from(comment, authorView(userId), List.of());
    }

    /**
     * 帖子评论树(分页):根评论时间倒序、子评论时间正序,total 为根评论数
     */
    public PageView<CommentView> listByPost(Long postId, int page, int limit) {
        List<Comment> roots = commentMapper.listRoots(postId, (page - 1) * limit, limit);
        List<Long> rootIds = roots.stream().map(Comment::getId).toList();
        // 按 root_id 分组子评论,避免 N+1 逐条查询;无根评论时跳过,防 IN () 非法 SQL
        Map<Long, List<Comment>> repliesByRoot = new HashMap<>();
        if (!rootIds.isEmpty()) {
            for (Comment reply : commentMapper.listByRootIds(rootIds)) {
                repliesByRoot.computeIfAbsent(reply.getRootId(), k -> new ArrayList<>()).add(reply);
            }
        }
        // 组装视图:根评论携带其 replies,二级回复不再下钻
        List<CommentView> views = roots.stream().map(root -> {
            List<CommentView> replies = repliesByRoot.getOrDefault(root.getId(), List.of())
                    .stream().map(c -> CommentView.from(c, authorView(c.getUserId()), List.of())).toList();
            return CommentView.from(root, authorView(root.getUserId()), replies);
        }).toList();
        return new PageView<>(views, commentMapper.countRoots(postId), page);
    }

    /**
     * 删除评论:仅作者可删,软删除并维护帖子评论计数 -1
     */
    public void delete(Long id, Long userId) {
        // 存在性判断用 selectById(不筛状态):空行或已软删(status=0)均视为不存在,避免重复删除再次递减计数
        Comment comment = commentMapper.selectById(id);
        if (comment == null || comment.getStatus() == 0) {
            throw new ApiException(404, "评论不存在");
        }
        // 仅评论作者本人可删,他人一律 403
        if (!comment.getUserId().equals(userId)) {
            throw new ApiException(403, "无权删除此评论");
        }
        commentMapper.softDelete(id);
        // 计数回退:帖子评论数 -1
        postMapper.changeCommentCount(comment.getPostId(), -1);
    }

    /** 查询作者视图:findById 不筛 status,作者被禁用仍展示其历史评论 */
    private UserView authorView(Long userId) {
        User u = userMapper.findById(userId);
        // 作者行缺失时返回 null,响应中 user 内嵌为 null(前端自行兜底)
        return u == null ? null : UserView.from(u);
    }
}
