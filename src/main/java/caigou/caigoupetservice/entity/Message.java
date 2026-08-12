package caigou.caigoupetservice.entity;

import lombok.Data;

/**
 * 聊天消息实体,对应 messages 表
 * clientMsgId 为客户端幂等去重标识(唯一键 uk_sender_client:sender_id + client_msg_id)
 * msgType=5 系统消息时 content 存 JSON 字符串(如宠物互动事件)
 */
@Data
public class Message {

    /** 主键(自增) */
    private Long id;
    /** 聊天室ID(外键→chat_rooms.id) */
    private Long roomId;
    /** 发送者ID(外键→users.id) */
    private Long senderId;
    /** 消息类型:0=文本 1=图 2=视频 3=文件 4=音频 5=系统 */
    private Integer msgType;
    /** 消息内容(msg_type=5 为 JSON 字符串) */
    private String content;
    /** 资源ID(可空,图/视频/文件等资源) */
    private Long resourceId;
    /** 回复的消息ID(可空) */
    private Long replyTo;
    /** 状态:1=正常 0=撤回 -1=删除 */
    private Integer status;
    /** 客户端消息ID(幂等去重,映射 client_msg_id) */
    private String clientMsgId;
    /** 创建时间(DB 维护,列表按 id 倒序分页) */
    private String createdAt;
}
