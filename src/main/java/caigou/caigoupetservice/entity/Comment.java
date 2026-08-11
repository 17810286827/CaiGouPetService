package caigou.caigoupetservice.entity;

import lombok.Data;

/**
 * 评论实体,对应 comments 表
 * 两级树形:一级评论 parent_id/root_id 为空,二级回复 parent_id 指向一级评论、root_id 指向其根
 * created_at/updated_at 由数据库维护,不参与 insert
 */
@Data
public class Comment {

    /** 主键(自增) */
    private Long id;
    /** 所属帖子ID(外键→posts.id) */
    private Long postId;
    /** 评论者ID(外键→users.id) */
    private Long userId;
    /** 父评论ID(一级评论为空) */
    private Long parentId;
    /** 根评论ID(回复挂到其根,一级评论为空) */
    private Long rootId;
    /** 评论内容 */
    private String content;
    /** 点赞数 */
    private Integer likeCount;
    /** 状态:1=正常 0=删除 */
    private Integer status;
    /** 创建时间(DB 维护,TIMESTAMP 原样映射) */
    private String createdAt;
    /** 更新时间(DB 维护,TIMESTAMP 原样映射) */
    private String updatedAt;
}
