package caigou.caigoupetservice.dto;

import caigou.caigoupetservice.entity.ChatRoom;
import caigou.caigoupetservice.entity.Message;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 聊天室视图:返回给前端,字段 snake_case 与 Express 对齐
 * last_message 为最后一条消息(可空),转成下划线字段 Map 避免实体驼峰字段泄漏
 */
@Data
@AllArgsConstructor
public class ChatRoomView {

    /** 主键ID */
    private Long id;
    /** 类型:1=私聊 2=群聊 */
    private Integer type;
    /** 聊天室名称(群聊用,私聊可空) */
    private String name;
    /** 头像URL(可空) */
    private String avatar_url;
    /** 创建者ID */
    private Long created_by;
    /** 创建时间 */
    private String created_at;
    /** 更新时间 */
    private String updated_at;
    /** 最后一条消息(可空;无消息为 null,序列化时保留字段) */
    private Map<String, Object> last_message;

    /**
     * 从实体构造视图:last_message 由消息实体转为下划线 Map,无消息时为 null
     * @param room    聊天室实体
     * @param lastMsg 最后一条消息(可空)
     * @return 聊天室视图
     */
    public static ChatRoomView from(ChatRoom room, Message lastMsg) {
        return new ChatRoomView(room.getId(), room.getType(), room.getName(), room.getAvatarUrl(),
                room.getCreatedBy(), room.getCreatedAt(), room.getUpdatedAt(), toMessageMap(lastMsg));
    }

    /**
     * 消息实体转下划线字段 Map(对齐 Express 消息 JSON 结构,与 socket 广播层保持一致)
     * @param m 消息实体
     * @return 下划线字段 Map,实体为 null 时返回 null
     */
    private static Map<String, Object> toMessageMap(Message m) {
        if (m == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", m.getId());
        map.put("room_id", m.getRoomId());
        map.put("sender_id", m.getSenderId());
        map.put("msg_type", m.getMsgType());
        map.put("content", m.getContent());
        map.put("resource_id", m.getResourceId());
        map.put("reply_to", m.getReplyTo());
        map.put("status", m.getStatus());
        map.put("client_msg_id", m.getClientMsgId());
        map.put("created_at", m.getCreatedAt());
        return map;
    }
}
