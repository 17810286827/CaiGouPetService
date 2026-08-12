package caigou.caigoupetservice.socket;

import com.corundumstudio.socketio.AuthorizationResult;
import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.listener.ConnectListener;
import com.corundumstudio.socketio.listener.DataListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * socket 服务配置:POC 最小验证——注册 chat:join 与 chat:message 两个事件
 * 端口 3001(环境变量 CAIGOPET_SOCKET_PORT 可覆盖),独立于 REST 端口 3000
 * 说明:netty-socketio 2.0.13 的授权回调返回 AuthorizationResult 而非 boolean,
 * 与 socket.io-client v4 的 auth 握手载荷存放在 HandshakeData.getAuthToken() 中
 * socket 服务默认不启动,设置 socket.enabled=true 时启用(测试环境不设置,
 * 避免多测试上下文重复绑定 3001 端口冲突;批次 2 接 socket 时开启)
 */
@Configuration
public class SocketConfig {

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
            // POC 阶段:暂不校验 token,仅打印;批次 2 换用 SocketAuthListener 真实校验
            System.out.println("[POC] handshake auth keys=" + data.getAuthToken());
            return AuthorizationResult.SUCCESSFUL_AUTHORIZATION;
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
}
