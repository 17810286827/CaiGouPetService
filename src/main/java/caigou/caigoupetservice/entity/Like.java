package caigou.caigoupetservice.entity;

import lombok.Data;

/**
 * 点赞实体,对应 likes 表
 * created_at 由数据库维护,不参与 insert
 * getter/setter 由 Lombok @Data 编译期生成
 */
@Data
public class Like {

    /** 主键(自增) */
    private Long id;

    /** 点赞者ID(外键→users.id) */
    private Long userId;

    /** 帖子ID(外键→posts.id) */
    private Long postId;

    /** 创建时间(DB 维护,TEXT 原样映射) */
    private String createdAt;
}
