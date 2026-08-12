package caigou.caigoupetservice.socket;

import caigou.caigoupetservice.entity.User;
import caigou.caigoupetservice.exception.ApiException;
import caigou.caigoupetservice.mapper.UserMapper;
import caigou.caigoupetservice.service.JwtService;
import com.corundumstudio.socketio.AuthorizationResult;
import com.corundumstudio.socketio.HandshakeData;
import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.listener.ConnectListener;
import com.corundumstudio.socketio.listener.DataListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * socket 服务配置:握手鉴权 + 注册 chat:join 与 chat:message 两个事件
 * 端口 3001(环境变量 CAIGOPET_SOCKET_PORT 可覆盖),独立于 REST 端口 3000
 * 说明:netty-socketio 2.0.13 的授权回调返回 AuthorizationResult 而非 boolean;
 * 鉴权取 token 双通道——socket.io v4 auth 载荷(auth:{token})或 URL query(?token=),
 * 因 2.0.13 在 engine.io 握手阶段 getAuthToken() 返回 null(POC 已验证),实际走 query 通道
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

    /** socket 服务实例:POC 阶段仅用于注册事件;批次 2 可注入业务层做主动推送 */
    private SocketIOServer server;

    /** 启动 socket 服务,并注册 POC 用事件处理器 */
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
        // 连接监听:POC 阶段仅打印客户端 sessionId,便于排查握手是否成功
        server.addConnectListener(new ConnectListener() {
            @Override
            public void onConnect(SocketIOClient client) {
                System.out.println("[POC] client connected: " + client.getSessionId());
            }
        });
        // chat:join:把客户端加入 room:{roomId},用于房间内广播
        server.addEventListener("chat:join", String.class, new DataListener<String>() {
            @Override
            public void onData(SocketIOClient client, String roomId, com.corundumstudio.socketio.AckRequest ack) {
                // ack 回调在 POC 阶段未使用:客户端可不带回调,仅单向 join
                client.joinRoom("room:" + roomId);
                System.out.println("[POC] joined room: " + roomId);
            }
        });
        // chat:message:收到消息后广播给同房间其它客户端(不含发送者)
        server.addEventListener("chat:message", Object.class, new DataListener<Object>() {
            @Override
            public void onData(SocketIOClient client, Object data, com.corundumstudio.socketio.AckRequest ack) {
                @SuppressWarnings("unchecked")
                Map<String, Object> payload = (Map<String, Object>) data;
                Object roomId = payload.get("room_id");
                // 从握手 auth 载荷中取 userId(v4 客户端 auth:{userId}),POC 阶段未传则记 unknown
                Object authToken = client.getHandshakeData().getAuthToken();
                Object userId = authToken instanceof Map<?, ?> ? ((Map<?, ?>) authToken).get("userId") : null;
                String senderId = userId == null ? "unknown" : String.valueOf(userId);
                // 房间广播:sendEvent 第二参传 client 作为排除客户端,仅发给同房间其它客户端(对齐 Express socket.to(room))
                server.getRoomOperations("room:" + roomId).sendEvent("chat:message", client,
                        Map.of("room_id", roomId, "user_id", senderId, "content", payload.get("content")));
            }
        });
        // 启动 socket 服务并绑定 3001 端口(独立于 REST 端口 3000)
        server.start();
        return server;
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
