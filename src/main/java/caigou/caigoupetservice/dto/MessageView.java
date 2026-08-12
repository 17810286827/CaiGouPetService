package caigou.caigoupetservice.dto;

import caigou.caigoupetservice.entity.Message;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 聊天消息视图:返回给前端,字段 snake_case 与 Express 对齐
 * sender 内嵌发送者用户信息(与 Express fullMsg.sender 一致)
 */
@Data
@AllArgsConstructor
public class MessageView {

    /** 主键ID */
    private Long id;
    /** 聊天室ID */
    private Long room_id;
    /** 发送者ID */
    private Long sender_id;
    /** 消息类型:0=文本 1=图 2=视频 3=文件 4=音频 5=系统 */
    private Integer msg_type;
    /** 消息内容(msg_type=5 为 JSON 字符串) */
    private String content;
    /** 资源ID(可空) */
    private Long resource_id;
    /** 回复的消息ID(可空) */
    private Long reply_to;
    /** 状态:1=正常 0=撤回 -1=删除 */
    private Integer status;
    /** 客户端消息ID(幂等去重) */
    private String client_msg_id;
    /** 创建时间(DB 维护) */
    private String created_at;
    /** 发送者用户信息(可空) */
    private UserView sender;

    /**
     * 从消息实体构造视图,内嵌发送者信息
     * @param m      消息实体
     * @param sender 发送者用户视图(可空)
     * @return 消息视图
     */
    public static MessageView from(Message m, UserView sender) {
        return new MessageView(m.getId(), m.getRoomId(), m.getSenderId(), m.getMsgType(),
                m.getContent(), m.getResourceId(), m.getReplyTo(), m.getStatus(), m.getClientMsgId(),
                m.getCreatedAt(), sender);
    }

    /**
     * 转为 socket 广播 payload(下划线字段 + sender 内嵌),对齐 Express fullMsg 结构
     * REST 落库成功后全房间广播 chat:message 时直接作为事件载荷
     * @return 下划线字段 Map
     */
    public Map<String, Object> toSocketPayload() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("room_id", room_id);
        map.put("sender_id", sender_id);
        map.put("msg_type", msg_type);
        map.put("content", content);
        map.put("resource_id", resource_id);
        map.put("reply_to", reply_to);
        map.put("status", status);
        map.put("client_msg_id", client_msg_id);
        map.put("created_at", created_at);
        if (sender != null) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("id", sender.id());
            s.put("username", sender.username());
            s.put("nickname", sender.nickname());
            s.put("avatar_url", sender.avatar_url());
            map.put("sender", s);
        }
        return map;
    }
}
