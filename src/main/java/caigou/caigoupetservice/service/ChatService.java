package caigou.caigoupetservice.service;

import caigou.caigoupetservice.dto.ChatRoomView;
import caigou.caigoupetservice.dto.MessageView;
import caigou.caigoupetservice.dto.UserView;
import caigou.caigoupetservice.entity.ChatRoom;
import caigou.caigoupetservice.entity.ChatRoomMember;
import caigou.caigoupetservice.entity.Message;
import caigou.caigoupetservice.entity.User;
import caigou.caigoupetservice.exception.ApiException;
import caigou.caigoupetservice.mapper.ChatRoomMapper;
import caigou.caigoupetservice.mapper.ChatRoomMemberMapper;
import caigou.caigoupetservice.mapper.MessageMapper;
import caigou.caigoupetservice.mapper.UserMapper;
import com.corundumstudio.socketio.SocketIOServer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
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
    private final UserMapper userMapper;
    /** socket 服务惰性注入:socket.enabled=false 时 bean 不存在,getIfAvailable() 返回 null 跳过推送 */
    private final ObjectProvider<SocketIOServer> socketProvider;

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
                .map(r -> ChatRoomView.from(r, lastMessage(r.getId()), membersOf(r.getId())))
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

    /**
     * 发送聊天消息:room_id/client_msg_id 必填(400),非成员 403,重复 client_msg_id 409
     * 成功落库后返回含 sender 的消息视图,并全房间广播 chat:message(socket 启用时)
     * @param userId 当前登录用户(发送者)
     * @param body   请求体(room_id/msg_type/content/resource_id/reply_to/client_msg_id)
     * @return 消息视图(含 sender 内嵌)
     */
    public MessageView sendMessage(Long userId, Map<String, Object> body) {
        Long roomId = body.get("room_id") == null ? null : Long.valueOf(String.valueOf(body.get("room_id")));
        String clientMsgId = body.get("client_msg_id") == null ? null : String.valueOf(body.get("client_msg_id"));
        if (roomId == null || clientMsgId == null) {
            throw new ApiException(400, "room_id 和 client_msg_id 不能为空");
        }
        if (chatRoomMemberMapper.find(roomId, userId) == null) {
            throw new ApiException(403, "不在聊天室中");
        }
        Message msg = new Message();
        msg.setRoomId(roomId);
        msg.setSenderId(userId);
        msg.setMsgType(body.get("msg_type") == null ? 0 : ((Number) body.get("msg_type")).intValue());
        msg.setContent(body.get("content") == null ? "" : String.valueOf(body.get("content")));
        if (body.get("resource_id") != null) {
            msg.setResourceId(Long.valueOf(String.valueOf(body.get("resource_id"))));
        }
        if (body.get("reply_to") != null) {
            msg.setReplyTo(Long.valueOf(String.valueOf(body.get("reply_to"))));
        }
        // insert 显式列 status(NOT NULL),必须赋非空值,否则违反约束
        msg.setStatus(1);
        msg.setClientMsgId(clientMsgId);
        try {
            messageMapper.insert(msg);
        } catch (DuplicateKeyException e) {
            // uk_sender_client 唯一键冲突:同一发送者重复 client_msg_id
            throw new ApiException(409, "消息重复");
        }
        // created_at 由 DB CURRENT_TIMESTAMP 维护,insert 只回填 id;重查回填后再构造视图与 socket payload
        msg = messageMapper.findById(msg.getId());
        MessageView view = MessageView.from(msg, userView(userId));
        pushMessage(roomId, view);
        return view;
    }

    /**
     * 获取房间消息历史:非成员 403,支持 before 游标分页,升序返回
     * 成功后更新当前成员的 last_read_msg_id = max(旧,本次最新消息 id),对齐 Express
     * @param userId 当前登录用户
     * @param roomId 聊天室ID
     * @param before 游标:只取 id < before 的更早消息(可空,空则取最新 limit 条)
     * @param limit  条数上限
     * @return 升序消息视图列表
     */
    public List<MessageView> getMessages(Long userId, Long roomId, Long before, int limit) {
        ChatRoomMember member = chatRoomMemberMapper.find(roomId, userId);
        if (member == null) {
            throw new ApiException(403, "不在聊天室中");
        }
        List<Message> rows = before != null
                ? messageMapper.listBefore(roomId, before, limit)
                : messageMapper.listLatest(roomId, limit);
        Collections.reverse(rows);
        // 更新已读:仅前移游标(升序列表最后一条为本次最新消息)
        if (!rows.isEmpty()) {
            long maxId = rows.get(rows.size() - 1).getId();
            if (maxId > member.getLastReadMsgId()) {
                member.setLastReadMsgId(maxId);
                chatRoomMemberMapper.updateLastRead(member);
            }
        }
        return rows.stream().map(m -> MessageView.from(m, userView(m.getSenderId()))).toList();
    }

    /**
     * 房间详情:非成员 403(文案与该接口不一致),返回 {room, members}
     * members 每个元素对齐 Express ChatRoomMember 含内嵌 User 的结构(前端 chatlist.js 依赖 m.user_id 与 m.User)
     * @param userId 当前登录用户
     * @param roomId 聊天室ID
     * @return {room: ChatRoomView, members: [成员Map]}
     */
    public Map<String, Object> roomDetail(Long userId, Long roomId) {
        if (chatRoomMemberMapper.find(roomId, userId) == null) {
            throw new ApiException(403, "不在该房间中");
        }
        // members 同时作为房间视图内嵌字段与顶层数组(对齐 Express room.toJSON() 含 ChatRoomMember[])
        List<Map<String, Object>> members = membersOf(roomId);
        return Map.of(
                "room", ChatRoomView.from(chatRoomMapper.findById(roomId), null, members),
                "members", members);
    }

    /**
     * 组装成员视图 Map:对齐 Express ChatRoomMember 含内嵌 User 的 snake_case 结构
     * 内嵌键必须为大写 User(Sequelize 默认别名=模型名),前端 chatlist.js 读 other.User;小写会导致私聊名退化
     * @param m 成员实体
     * @return {id, room_id, user_id, role, last_read_msg_id, created_at, User}
     */
    private Map<String, Object> memberView(ChatRoomMember m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", m.getId());
        map.put("room_id", m.getRoomId());
        map.put("user_id", m.getUserId());
        map.put("role", m.getRole());
        map.put("last_read_msg_id", m.getLastReadMsgId());
        map.put("created_at", m.getCreatedAt());
        UserView u = userView(m.getUserId());
        if (u != null) {
            map.put("User", u);
        }
        return map;
    }

    /**
     * 查询房间全部成员的视图 Map 列表(每项含大写 User 内嵌)
     * @param roomId 聊天室ID
     * @return 成员视图列表
     */
    private List<Map<String, Object>> membersOf(Long roomId) {
        return chatRoomMemberMapper.listByRoom(roomId).stream()
                .map(this::memberView).toList();
    }

    /**
     * 全房间广播 chat:message(io.to 语义,含发送者;前端按 client_msg_id 去重)
     * socket 未启用(socket.enabled=false 时 bean 不存在)则静默跳过,不影响 REST 主流程
     * @param roomId 聊天室ID
     * @param view   已落库消息视图
     */
    private void pushMessage(Long roomId, MessageView view) {
        SocketIOServer server = socketProvider.getIfAvailable();
        if (server == null) {
            log.debug("[chat] socket 未启用,跳过实时推送 roomId={}, messageId={}", roomId, view.getId());
            return;
        }
        try {
            server.getRoomOperations("room:" + roomId).sendEvent("chat:message", view.toSocketPayload());
            log.info("[chat] socket 推送 chat:message roomId={}, messageId={}", roomId, view.getId());
        } catch (Exception e) {
            // 推送失败不影响 REST 响应,记录日志便于排查
            log.warn("[chat] socket 推送失败 roomId={}, messageId={}: {}", roomId, view.getId(), e.getMessage());
        }
    }

    /**
     * 按用户ID查询并转为安全视图(用户不存在返回 null)
     * @param userId 用户ID
     * @return 用户视图或 null
     */
    private UserView userView(Long userId) {
        User u = userMapper.findById(userId);
        return u == null ? null : UserView.from(u);
    }
}
