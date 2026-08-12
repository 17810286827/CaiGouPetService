package caigou.caigoupetservice.service;

import caigou.caigoupetservice.dto.ChatRoomView;
import caigou.caigoupetservice.entity.ChatRoom;
import caigou.caigoupetservice.entity.ChatRoomMember;
import caigou.caigoupetservice.entity.Message;
import caigou.caigoupetservice.mapper.ChatRoomMapper;
import caigou.caigoupetservice.mapper.ChatRoomMemberMapper;
import caigou.caigoupetservice.mapper.MessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 聊天室业务:创建房间(私聊幂等复用)与当前用户房间列表(含最后一条消息)
 * 业务异常统一抛 ApiException,路由层只负责状态码与返回组装
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatRoomMapper chatRoomMapper;
    private final ChatRoomMemberMapper chatRoomMemberMapper;
    private final MessageMapper messageMapper;

    /**
     * 创建聊天室:type=1 私聊时先查已存在的双向房间,命中则复用(created=false)
     * 未命中则新建房间并写入创建者(role=2)与其余成员(role=0)
     * @param userId 当前登录用户(创建者)
     * @param body   请求体:type(默认1)/name/member_ids(成员ID列表)
     * @return {room: ChatRoomView, created: Boolean} 供路由层按 created 决定 201/200
     */
    public Map<String, Object> createRoom(Long userId, Map<String, Object> body) {
        int type = ((Number) body.getOrDefault("type", 1)).intValue();
        String name = body.get("name") == null ? "" : String.valueOf(body.get("name"));
        @SuppressWarnings("unchecked")
        List<Integer> ids = (List<Integer>) body.getOrDefault("member_ids", List.of());
        List<Long> memberIds = ids.stream().map(Long::valueOf).toList();

        // 私聊幂等复用:创建者为我且双方均为成员的房间已存在时直接返回(对齐 Express 双向幂等)
        if (type == 1 && !memberIds.isEmpty()) {
            ChatRoom existing = chatRoomMapper.findPrivateRoom(userId, memberIds.get(0));
            if (existing != null) {
                log.info("[chat] 复用私聊房间 roomId={}, creator={}, other={}", existing.getId(), userId, memberIds.get(0));
                return Map.of("room", ChatRoomView.from(existing, lastMessage(existing.getId())), "created", false);
            }
        }

        // 新建房间并回填自增主键
        ChatRoom room = new ChatRoom();
        room.setType(type);
        room.setName(name);
        room.setCreatedBy(userId);
        chatRoomMapper.insert(room);

        // 创建者本人以 role=2 入房;last_read_msg_id 非空约束,新成员从 0 起算
        ChatRoomMember me = new ChatRoomMember();
        me.setRoomId(room.getId());
        me.setUserId(userId);
        me.setRole(2);
        me.setLastReadMsgId(0L);
        chatRoomMemberMapper.insert(me);

        // 其余成员以 role=0 入房(去重自己,防重复入房)
        for (Long mid : memberIds) {
            if (!mid.equals(userId)) {
                ChatRoomMember m = new ChatRoomMember();
                m.setRoomId(room.getId());
                m.setUserId(mid);
                m.setRole(0);
                m.setLastReadMsgId(0L);
                chatRoomMemberMapper.insert(m);
            }
        }
        log.info("[chat] 创建房间 roomId={}, type={}, creator={}, members={}", room.getId(), type, userId, memberIds);
        return Map.of("room", ChatRoomView.from(room, null), "created", true);
    }

    /**
     * 当前用户参与的房间列表:每房间取最后一条消息(无消息为 null),按更新时间倒序
     * @param userId 当前登录用户
     * @return 房间视图列表
     */
    public List<ChatRoomView> listRooms(Long userId) {
        return chatRoomMapper.listByUserId(userId).stream()
                .map(r -> ChatRoomView.from(r, lastMessage(r.getId())))
                .toList();
    }

    /**
     * 查询房间最近一条正常消息,无消息返回 null
     * @param roomId 聊天室ID
     * @return 消息实体或 null
     */
    private Message lastMessage(Long roomId) {
        List<Message> msgs = messageMapper.listLatest(roomId, 1);
        return msgs.isEmpty() ? null : msgs.get(0);
    }
}
