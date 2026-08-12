package caigou.caigoupetservice.controller;

import caigou.caigoupetservice.dto.ChatRoomView;
import caigou.caigoupetservice.dto.MessageView;
import caigou.caigoupetservice.service.ChatService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 聊天控制器:创建聊天室/当前用户房间列表/发送消息/历史消息/房间详情
 * 本层仅做参数接收与返回值组装,业务在 service 层
 * 认证说明:所有端点均需登录(拦截器校验,不加 @PublicEndpoint)
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    /**
     * 创建聊天室:私聊幂等复用返回 200,新建返回 201,响应体与 Express 一致为 {room}
     * @param body    请求体(type/name/member_ids,用 Map 接收成员ID列表)
     * @param request 用于取当前登录用户ID
     * @return 201(新建)/200(复用) + {room}
     */
    @PostMapping("/rooms")
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body,
                                                      HttpServletRequest request) {
        // 当前登录用户ID由 JWT 拦截器写入 request attribute,创建者取它而非请求体
        Long userId = (Long) request.getAttribute("currentUserId");
        Map<String, Object> result = chatService.createRoom(userId, body);
        boolean created = (Boolean) result.get("created");
        // 对齐 Express:响应体只回显 room,created 仅用于路由层决定 201/200
        return ResponseEntity.status(created ? 201 : 200).body(Map.of("room", result.get("room")));
    }

    /**
     * 当前用户参与的房间列表(含每房间最后一条消息),按更新时间倒序
     * @param request 用于取当前登录用户ID
     * @return {rooms: [ChatRoomView]}
     */
    @GetMapping("/rooms")
    public Map<String, List<ChatRoomView>> list(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("currentUserId");
        return Map.of("rooms", chatService.listRooms(userId));
    }

    /**
     * 发送消息:成功 201 返回 {message}(含 sender 内嵌),并全房间 socket 推送
     * 400 参数缺失/403 非成员/409 重复 client_msg_id 由 service 抛 ApiException 统一处理
     * @param body    请求体(room_id/msg_type/content/resource_id/reply_to/client_msg_id)
     * @param request 用于取当前登录用户ID
     * @return 201 + {message}
     */
    @PostMapping("/messages")
    public ResponseEntity<Map<String, MessageView>> sendMessage(@RequestBody Map<String, Object> body,
                                                                HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("currentUserId");
        return ResponseEntity.status(201).body(Map.of("message", chatService.sendMessage(userId, body)));
    }

    /**
     * 历史消息:支持 before 游标分页(缺省取最新 limit 条),升序返回,成功后更新已读游标
     * @param roomId  聊天室ID(路径)
     * @param before  游标:只返回该 id 之前的消息(可空)
     * @param limit   条数上限,默认 50
     * @param request 用于取当前登录用户ID
     * @return {messages: [MessageView]} 升序
     */
    @GetMapping("/rooms/{roomId}/messages")
    public Map<String, List<MessageView>> getMessages(@PathVariable Long roomId,
                                                      @RequestParam(required = false) Long before,
                                                      @RequestParam(defaultValue = "50") int limit,
                                                      HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("currentUserId");
        return Map.of("messages", chatService.getMessages(userId, roomId, before, limit));
    }

    /**
     * 房间详情:返回 {room, members}(成员为用户信息列表)
     * @param roomId  聊天室ID(路径)
     * @param request 用于取当前登录用户ID
     * @return {room: ChatRoomView, members: [UserView]}
     */
    @GetMapping("/rooms/{roomId}")
    public Map<String, Object> roomDetail(@PathVariable Long roomId, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("currentUserId");
        return chatService.roomDetail(userId, roomId);
    }
}
