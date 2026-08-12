package caigou.caigoupetservice.controller;

import caigou.caigoupetservice.dto.ChatRoomView;
import caigou.caigoupetservice.service.ChatService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 聊天控制器:创建聊天室/当前用户房间列表
 * 本层仅做参数接收与返回值组装,业务在 service 层
 * 认证说明:两个端点均需登录(拦截器校验,不加 @PublicEndpoint)
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
}
