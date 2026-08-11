package caigou.caigoupetservice.entity;

import lombok.Data;

/**
 * 关注实体,对应 follows 表
 * userId=被关注者,followerId=关注者
 * created_at 由数据库维护,不参与 insert
 */
@Data
public class Follow {

    /** 主键(自增) */
    private Long id;

    /** 被关注者ID(外键→users.id) */
    private Long userId;

    /** 关注者ID(外键→users.id) */
    private Long followerId;

    /** 创建时间(DB 维护,TEXT 原样映射) */
    private String createdAt;
}
