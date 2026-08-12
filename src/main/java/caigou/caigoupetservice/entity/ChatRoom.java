package caigou.caigoupetservice.entity;

import lombok.Data;

/**
 * 聊天室实体,对应 chat_rooms 表
 * type=1 私聊 / 2 群聊;私聊房间由双方成员记录组成,房间本身不冗余成员
 * created_at/updated_at 由数据库维护,不参与 insert
 */
@Data
public class ChatRoom {

    /** 主键(自增) */
    private Long id;
    /** 类型:1=私聊 2=群聊 */
    private Integer type;
    /** 聊天室名称(群聊用,私聊可空) */
    private String name;
    /** 头像URL(可空) */
    private String avatarUrl;
    /** 创建者ID(外键→users.id) */
    private Long createdBy;
    /** 创建时间(DB 维护,TIMESTAMP 原样映射为字符串) */
    private String createdAt;
    /** 更新时间(DB 维护,群聊列表按此倒序) */
    private String updatedAt;
}
