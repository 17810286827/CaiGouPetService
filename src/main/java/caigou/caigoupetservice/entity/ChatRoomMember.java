package caigou.caigoupetservice.entity;

import lombok.Data;

/**
 * 聊天室成员实体,对应 chat_room_members 表
 * 同一房间内 user_id 唯一(唯一键 uk_room_user),last_read_msg_id 用于已读游标
 */
@Data
public class ChatRoomMember {

    /** 主键(自增) */
    private Long id;
    /** 聊天室ID(外键→chat_rooms.id) */
    private Long roomId;
    /** 成员ID(外键→users.id) */
    private Long userId;
    /** 角色:2=创建者 0=成员 */
    private Integer role;
    /** 最后已读消息ID(默认 0) */
    private Long lastReadMsgId;
    /** 加入时间(DB 维护) */
    private String createdAt;
}
