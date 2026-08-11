package caigou.caigoupetservice.entity;

import lombok.Data;

/**
 * 收藏实体,对应 favorites 表(字段与 likes 表一致)
 * created_at 由数据库维护,不参与 insert
 */
@Data
public class Favorite {

    /** 主键(自增) */
    private Long id;

    /** 收藏者ID(外键→users.id) */
    private Long userId;

    /** 帖子ID(外键→posts.id) */
    private Long postId;

    /** 创建时间(DB 维护,TEXT 原样映射) */
    private String createdAt;
}
