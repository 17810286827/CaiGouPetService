package caigou.caigoupetservice.dto;

import caigou.caigoupetservice.entity.Comment;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 评论视图:返回给前端,含作者 user 内嵌与 replies 子评论树,字段 snake_case
 */
@Data
@AllArgsConstructor
public class CommentView {

    /** 主键ID */
    private Long id;
    /** 所属帖子ID */
    private Long post_id;
    /** 评论者ID */
    private Long user_id;
    /** 父评论ID(一级评论为空) */
    private Long parent_id;
    /** 根评论ID(一级评论为空) */
    private Long root_id;
    /** 评论内容 */
    private String content;
    /** 点赞数 */
    private Integer like_count;
    /** 状态:1=正常 0=删除 */
    private Integer status;
    /** 创建时间 */
    private String created_at;
    /** 作者信息 */
    private UserView user;
    /** 子评论列表(二级回复) */
    private List<CommentView> replies;

    /** 从实体构造视图(一级评论传空列表,二级回复不再下钻) */
    public static CommentView from(Comment c, UserView author, List<CommentView> replies) {
        return new CommentView(c.getId(), c.getPostId(), c.getUserId(), c.getParentId(), c.getRootId(),
                c.getContent(), c.getLikeCount(), c.getStatus(), c.getCreatedAt(), author, replies);
    }
}
