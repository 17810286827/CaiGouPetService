package caigou.caigoupetservice.socket;

import caigou.caigoupetservice.entity.Message;
import caigou.caigoupetservice.entity.User;
import caigou.caigoupetservice.exception.ApiException;
import caigou.caigoupetservice.mapper.UserMapper;
import caigou.caigoupetservice.service.JwtService;
import caigou.caigoupetservice.service.PetInteractionService;
import com.corundumstudio.socketio.AuthorizationResult;
import com.corundumstudio.socketio.HandshakeData;
import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * socket 服务配置:握手鉴权 + chat 事件全集 + pet:interact 宠物互动事件
 * 覆盖事件:chat:join / chat:leave / chat:typing / chat:stop_typing / chat:read / chat:message / pet:interact,
 * 其中 chat:join 会向房间内其它成员广播 chat:user_joined
 * pet:interact 成功时发送者收 ack、同房间其它成员收广播,失败统一收 pet:interact_reject
 * 端口 3001(环境变量 CAIGOPET_SOCKET_PORT 可覆盖),独立于 REST 端口 3000
 * 说明:netty-socketio 2.0.13 的授权回调返回 AuthorizationResult 而非 boolean;
 * 鉴权取 token 双通道——socket.io v4 auth 载荷(auth:{token})或 URL query(?token=),
 * 因 2.0.13 在 engine.io 握手阶段 getAuthToken() 返回 null(POC 已验证),实际走 query 通道
 * 事件阶段同样不能依赖 getAuthToken() 取用户(该载荷在事件阶段不可用),
 * 统一走 resolveToken → parseUserId → findById 链路实时重取,保证 user_id 为真实值而非 "unknown"
 * socket 服务默认不启动,设置 socket.enabled=true 时启用(测试环境不设置,
 * 避免多测试上下文重复绑定 3001 端口冲突)
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class SocketConfig {

    /** JWT 服务:解析握手 token 中的 userId,非法/过期抛 ApiException */
    private final JwtService jwtService;
    /** 用户数据访问:复核用户是否存在且未禁用(status=1),对齐 REST 拦截器语义 */
    private final UserMapper userMapper;
    /** 宠物互动业务:串门权限/冷却校验与 msg_type=5 消息落库,pet:interact 事件处理依赖 */
    private final PetInteractionService petInteractionService;

    /** socket 服务实例:事件注册与房间广播均基于它;批次 2 可注入业务层做主动推送 */
    private SocketIOServer server;

    /** 启动 socket 服务,并注册 chat 事件全集 */
    @Bean(destroyMethod = "stop")
    @ConditionalOnProperty(prefix = "socket", name = "enabled", havingValue = "true", matchIfMissing = false)
    public SocketIOServer socketIOServer() {
        com.corundumstudio.socketio.Configuration config =
                new com.corundumstudio.socketio.Configuration();
        // 监听 localhost:3001(可用 CAIGOPET_SOCKET_PORT 覆盖),与 REST 3000 解耦
        config.setHostname("localhost");
        config.setPort(Integer.parseInt(System.getenv().getOrDefault("CAIGOPET_SOCKET_PORT", "3001")));
        config.setAuthorizationListener(data -> {
            String token = resolveToken(data);
            if (token == null || token.isBlank()) {
                log.warn("[socket] 握手拒绝:未提供 token");
                return AuthorizationResult.FAILED_AUTHORIZATION;
            }
            try {
                // 验签解析 userId,非法/过期抛 ApiException
                Long userId = jwtService.parseUserId(token);
                // 镜像 REST JwtAuthInterceptor:验签后查库复核用户存在且未禁用
                User user = userMapper.findById(userId);
                if (user == null || user.getStatus() == null || user.getStatus() != 1) {
                    log.warn("[socket] 握手拒绝:用户不存在或已禁用, userId={}", userId);
                    return AuthorizationResult.FAILED_AUTHORIZATION;
                }
                log.info("[socket] 握手通过: userId={}", userId);
                return AuthorizationResult.SUCCESSFUL_AUTHORIZATION;
            } catch (ApiException e) {
                // 令牌过期/无效,按业务异常文案记录并拒绝握手
                log.warn("[socket] 握手拒绝:{}", e.getMessage());
                return AuthorizationResult.FAILED_AUTHORIZATION;
            }
        });

        server = new SocketIOServer(config);
        // 连接监听:记录 sessionId,便于排查握手是否成功
        server.addConnectListener(client ->
                log.info("[socket] 客户端连接: sessionId={}", client.getSessionId()));
        // 注册 chat 事件全集,逻辑拆分到私有方法保持本方法精简
        registerChatEvents();
        // 注册 pet:interact 宠物互动事件(串门等),逻辑拆分到私有方法
        registerPetInteractEvent();
        // 启动 socket 服务并绑定 3001 端口(独立于 REST 端口 3000)
        server.start();
        return server;
    }

    /**
     * 暴露 socket 服务实例:供业务层(REST 落库后)做主动实时推送
     * 注:bean 仅在 socket.enabled=true 时存在,注入方应使用 ObjectProvider 并判空
     * @return socket 服务实例
     */
    public SocketIOServer getServer() {
        return server;
    }

    /**
     * 注册 chat 事件全集
     * 契约对齐 Express socket/index.js:chat:user_joined 与 chat:message 排除发送者(对应 socket.to),
     * chat:typing / chat:stop_typing / chat:read 广播给房间内所有客户端含发送者(对应 io.to)
     */
    private void registerChatEvents() {
        // chat:join:加入 room:{roomId},并广播 chat:user_joined 给同房间其它成员(排除发送者)
        server.addEventListener("chat:join", String.class, (client, roomId, ack) -> {
            if (roomId == null || roomId.isBlank()) {
                log.warn("[socket] chat:join 忽略:roomId 为空, sessionId={}", client.getSessionId());
                return;
            }
            Map<String, Object> user = currentUser(client);
            if (user == null) {
                log.warn("[socket] chat:join 忽略:解析用户失败, sessionId={}", client.getSessionId());
                return;
            }
            client.joinRoom("room:" + roomId);
            // 通知房间内已有成员:新成员加入;发送者自身无需再收,故排除(对齐 Express socket.to)
            server.getRoomOperations("room:" + roomId).sendEvent("chat:user_joined", client,
                    Map.of("user_id", user.get("userId"), "nickname", user.get("nickname"),
                            "avatar_url", user.get("avatarUrl")));
            log.info("[socket] chat:join: room={}, userId={}", roomId, user.get("userId"));
        });

        // chat:leave:离开房间,无需广播(对齐 Express socket.leave)
        server.addEventListener("chat:leave", String.class, (client, roomId, ack) -> {
            if (roomId == null || roomId.isBlank()) {
                log.warn("[socket] chat:leave 忽略:roomId 为空, sessionId={}", client.getSessionId());
                return;
            }
            client.leaveRoom("room:" + roomId);
            log.info("[socket] chat:leave: room={}, sessionId={}", roomId, client.getSessionId());
        });

        // chat:typing:广播输入中状态,发给房间内所有客户端含发送者(对齐 Express io.to)
        server.addEventListener("chat:typing", Object.class, (client, data, ack) -> {
            Map<String, Object> payload = asMap(data);
            if (payload == null || payload.get("room_id") == null) {
                log.warn("[socket] chat:typing 忽略:缺少 room_id, sessionId={}", client.getSessionId());
                return;
            }
            Map<String, Object> user = currentUser(client);
            if (user == null) {
                log.warn("[socket] chat:typing 忽略:解析用户失败, sessionId={}", client.getSessionId());
                return;
            }
            Object roomId = payload.get("room_id");
            server.getRoomOperations("room:" + roomId).sendEvent("chat:typing",
                    Map.of("room_id", roomId, "user_id", user.get("userId"), "nickname", user.get("nickname")));
            log.info("[socket] chat:typing 广播: room={}, userId={}", roomId, user.get("userId"));
        });

        // chat:stop_typing:广播停止输入状态,发给房间内所有客户端含发送者(对齐 Express io.to)
        server.addEventListener("chat:stop_typing", Object.class, (client, data, ack) -> {
            Map<String, Object> payload = asMap(data);
            if (payload == null || payload.get("room_id") == null) {
                log.warn("[socket] chat:stop_typing 忽略:缺少 room_id, sessionId={}", client.getSessionId());
                return;
            }
            Map<String, Object> user = currentUser(client);
            if (user == null) {
                log.warn("[socket] chat:stop_typing 忽略:解析用户失败, sessionId={}", client.getSessionId());
                return;
            }
            Object roomId = payload.get("room_id");
            server.getRoomOperations("room:" + roomId).sendEvent("chat:stop_typing",
                    Map.of("room_id", roomId, "user_id", user.get("userId")));
            log.info("[socket] chat:stop_typing 广播: room={}, userId={}", roomId, user.get("userId"));
        });

        // chat:read:广播已读回执,需 room_id 与 message_id 齐备(对齐 Express 校验),发给房间内所有客户端
        server.addEventListener("chat:read", Object.class, (client, data, ack) -> {
            Map<String, Object> payload = asMap(data);
            if (payload == null) {
                log.warn("[socket] chat:read 忽略:payload 非对象, sessionId={}", client.getSessionId());
                return;
            }
            Object roomId = payload.get("room_id");
            Object messageId = payload.get("message_id");
            if (roomId == null || messageId == null) {
                log.warn("[socket] chat:read 忽略:缺少 room_id 或 message_id, sessionId={}", client.getSessionId());
                return;
            }
            Map<String, Object> user = currentUser(client);
            if (user == null) {
                log.warn("[socket] chat:read 忽略:解析用户失败, sessionId={}", client.getSessionId());
                return;
            }
            server.getRoomOperations("room:" + roomId).sendEvent("chat:read",
                    Map.of("room_id", roomId, "message_id", messageId, "user_id", user.get("userId")));
            log.info("[socket] chat:read 广播: room={}, messageId={}, userId={}", roomId, messageId, user.get("userId"));
        });

        // chat:message:转发消息给同房间其它客户端(排除发送者,对齐 Express socket.to),payload 原样转发并补 user_id
        server.addEventListener("chat:message", Object.class, (client, data, ack) -> {
            Map<String, Object> payload = asMap(data);
            if (payload == null || payload.get("room_id") == null) {
                log.warn("[socket] chat:message 忽略:缺少 room_id, sessionId={}", client.getSessionId());
                return;
            }
            Map<String, Object> user = currentUser(client);
            if (user == null) {
                log.warn("[socket] chat:message 忽略:解析用户失败, sessionId={}", client.getSessionId());
                return;
            }
            Object roomId = payload.get("room_id");
            // 复制原 payload 并补上服务端解析的 user_id,保留 content 等前端字段(避免修改入参 map)
            Map<String, Object> out = new HashMap<>(payload);
            out.put("user_id", user.get("userId"));
            server.getRoomOperations("room:" + roomId).sendEvent("chat:message", client, out);
            log.info("[socket] chat:message 转发: room={}, from userId={}", roomId, user.get("userId"));
        });
    }

    /**
     * 注册 pet:interact 宠物互动事件(串门/捣乱等 8 动作)
     * 契约对齐 Express socket/index.js L101-146:
     * 参数 {room_id, action_id, client_event_id} 任一缺失 → INVALID_REQUEST reject;
     * 业务失败(动作未知/非成员/串门关闭/冷却中)→ pet:interact_reject {event_id, code, message, retry_after_ms?};
     * 成功 → 发送者收 pet:interact_ack {event_id, cooldown_until},同房间其它成员收 pet:interact 广播(排除发送者)
     */
    private void registerPetInteractEvent() {
        server.addEventListener("pet:interact", Map.class, (client, data, ack) -> {
            Map<String, Object> payload = asMap(data);
            if (payload == null) {
                log.warn("[socket] pet:interact 忽略:payload 非对象, sessionId={}", client.getSessionId());
                return;
            }
            Object roomIdObj = payload.get("room_id");
            Object actionId = payload.get("action_id");
            Object eventIdObj = payload.get("client_event_id");
            String eventId = eventIdObj == null ? null : eventIdObj.toString();
            // 参数校验:room_id/action_id/client_event_id 任一缺失 → INVALID_REQUEST(对齐 Express)
            Long roomId = roomIdObj == null ? null : toLong(roomIdObj);
            if (roomId == null || actionId == null || eventId == null) {
                Map<String, Object> reject = new HashMap<>();
                reject.put("event_id", eventId);
                reject.put("code", "INVALID_REQUEST");
                reject.put("message", "参数不完整");
                client.sendEvent("pet:interact_reject", reject);
                log.warn("[socket] pet:interact_reject: 参数不完整, sessionId={}", client.getSessionId());
                return;
            }
            Map<String, Object> user = currentUser(client);
            if (user == null) {
                log.warn("[socket] pet:interact 忽略:解析用户失败, sessionId={}", client.getSessionId());
                return;
            }
            try {
                Map<String, Object> result = petInteractionService.handlePetInteract(
                        roomId, (Long) user.get("userId"), actionId.toString(), eventId);
                // 业务失败:按 service 返回的 code/message 组装 reject,冷却场景附带 retry_after_ms
                if (!Boolean.TRUE.equals(result.get("ok"))) {
                    Map<String, Object> reject = new HashMap<>();
                    reject.put("event_id", eventId);
                    reject.put("code", result.get("code"));
                    reject.put("message", result.get("message"));
                    Object retryAfterMs = result.get("retryAfterMs");
                    if (retryAfterMs != null) {
                        reject.put("retry_after_ms", retryAfterMs);
                    }
                    client.sendEvent("pet:interact_reject", reject);
                    log.info("[socket] pet:interact_reject: room={}, userId={}, code={}",
                            roomId, user.get("userId"), result.get("code"));
                    return;
                }
                // 成功:ack 回执发送者(cooldown_until 为下次可互动时间)
                Object cooldownUntil = result.get("cooldownUntil");
                client.sendEvent("pet:interact_ack", Map.of(
                        "event_id", eventId,
                        "cooldown_until", cooldownUntil == null ? "" : cooldownUntil.toString()));
                // 广播给同房间其它成员(排除发送者,对齐 Express socket.to)
                @SuppressWarnings("unchecked")
                Map<String, String> action = (Map<String, String>) result.get("action");
                Message msg = (Message) result.get("message");
                Map<String, Object> out = new HashMap<>();
                out.put("event_id", eventId);
                out.put("room_id", roomId);
                out.put("action_id", action.get("id"));
                out.put("action_label", action.get("label"));
                out.put("message", messageToMap(msg));
                out.put("from", Map.of("id", user.get("userId"),
                        "nickname", user.get("nickname"), "avatar_url", user.get("avatarUrl")));
                out.put("created_at", msg == null ? null : msg.getCreatedAt());
                server.getRoomOperations("room:" + roomId).sendEvent("pet:interact", client, out);
                log.info("[socket] pet:interact 广播: room={}, userId={}, action={}",
                        roomId, user.get("userId"), action.get("id"));
            } catch (Exception e) {
                // 兜底:未预期异常统一返回 SERVER_ERROR,避免连接被异常中断
                log.error("[socket] pet:interact 处理异常: {}", e.getMessage(), e);
                Map<String, Object> reject = new HashMap<>();
                reject.put("event_id", eventId);
                reject.put("code", "SERVER_ERROR");
                reject.put("message", "服务器开小差了");
                client.sendEvent("pet:interact_reject", reject);
            }
        });
    }

    /**
     * 将事件参数统一转为 Long 值
     * netty-socketio 反序列化 JSON 数字为 Integer/Long,字符串场景(如 query 透传)也兼容
     * @param v 原始参数值
     * @return Long 值,非法数字或 null 返回 null
     */
    private Long toLong(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                log.warn("[socket] 参数转 Long 失败: {}", s);
                return null;
            }
        }
        return null;
    }

    /**
     * 将 Message 实体转为下划线字段的广播 payload(对齐 Express 消息 JSON 结构)
     * 实体驼峰字段(roomId 等)与前端契约不符,统一在此转成 room_id/sender_id/... 供广播使用
     * @param m 消息实体
     * @return 下划线字段 Map,实体为 null 时返回 null
     */
    private Map<String, Object> messageToMap(Message m) {
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

    /**
     * 解析事件阶段当前用户信息
     * 说明:握手 auth 载荷(getAuthToken)在事件阶段不可用,不能据此取 userId,
     * 因此统一走 resolveToken → parseUserId → findById 链路实时重取,修复原 POC 中 user_id 恒为 "unknown" 的问题
     * @param client 当前 socket 客户端
     * @return {userId,nickname,avatarUrl} 的 Map,解析失败返回 null(调用方已做兜底忽略)
     */
    private Map<String, Object> currentUser(SocketIOClient client) {
        try {
            String token = resolveToken(client.getHandshakeData());
            if (token == null || token.isBlank()) {
                log.warn("[socket] 事件取用户失败:握手数据无 token, sessionId={}", client.getSessionId());
                return null;
            }
            Long userId = jwtService.parseUserId(token);
            User user = userMapper.findById(userId);
            if (user == null) {
                log.warn("[socket] 事件取用户失败:用户不存在, userId={}", userId);
                return null;
            }
            // nickname/avatarUrl 可为空,而 Map.of 禁止 null 值,统一归一化为空串避免序列化异常
            String nickname = user.getNickname() == null ? "" : user.getNickname();
            String avatarUrl = user.getAvatarUrl() == null ? "" : user.getAvatarUrl();
            return Map.of("userId", userId, "nickname", nickname, "avatarUrl", avatarUrl);
        } catch (ApiException e) {
            // token 过期/无效(事件阶段极少数情况),记录后返回 null 由调用方兜底
            log.warn("[socket] 事件取用户失败:{}", e.getMessage());
            return null;
        }
    }

    /**
     * 将事件 payload 统一转换为 Map 结构
     * netty-socketio 反序列化 JSON 对象为 LinkedHashMap,事件 handler 内先经此方法归一化
     * @param data 事件原始载荷
     * @return 转换后的 Map,非对象结构返回 null
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object data) {
        return data instanceof Map<?, ?> ? (Map<String, Object>) data : null;
    }

    /**
     * 从握手数据中解析 token:优先取 socket.io v4 的 auth 载荷(auth:{token}),
     * 兜底取 URL query(?token=),双通道兼容
     * 注:netty-socketio 2.0.13 在 engine.io 握手阶段 getAuthToken() 返回 null(POC 已验证),
     * 因此实际鉴权走 query 通道;保留 auth 通道以兼容新版本或后续升级
     * @param data 握手数据(含 auth 载荷与 url params)
     * @return token 字符串,未携带返回 null
     */
    private String resolveToken(HandshakeData data) {
        Object auth = data.getAuthToken();
        if (auth instanceof Map<?, ?> map) {
            Object token = map.get("token");
            if (token != null) {
                return token.toString();
            }
        } else if (auth instanceof String s && !s.isBlank()) {
            // 兼容极端情况:auth 载荷直接是 token 字符串而非对象
            return s;
        }
        // netty-socketio 2.0.13 握手阶段 auth 为空时的兜底:URL query 传 token
        return data.getSingleUrlParam("token");
    }
}
