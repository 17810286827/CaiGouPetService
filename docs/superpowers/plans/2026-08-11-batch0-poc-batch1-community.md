# 接口迁移批次 0+1 实现计划 — POC 门禁与社区模块

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**Goal:** 先验证 netty-socketio 与前端 socket.io-client v4 的真实连通性（POC 门禁），再完成基础设施（13 张表 + 拦截器公开 GET 放行 + 静态资源 + 上传）与社区模块（resources/users/posts/likes/favorites/follows/comments）迁移到 Spring Boot，前端 REST 零改动。

**Architecture:** 沿用认证模块分层（controller 薄/service 厚/mapper 注解 SQL/entity 映射表/dto 出入参）。POC 用最小 `SocketIOServer`（端口 3001）验证 netty-socketio 2.0.13 与 socket.io-client 4.8.3 双向收发；通过后批次 2 再扩展完整 socket 事件。社区模块逐条对齐旧 Express 契约（路径/参数/返回/状态码/错误文案）。

**Tech Stack:** Spring Boot 4.0.7 + Java 21 + MyBatis 注解式 + MySQL `caigoupet` + Lombok + Jackson（手写 ObjectMapper）+ netty-socketio 2.0.13 + node socket.io-client 4.8.3（POC 脚本）。

## Global Constraints

- **编码规范（用户强制）**：所有新 entity/dto 一律 **Lombok 注解**（`@Data`/`@Getter`/`@RequiredArgsConstructor`），**禁用 Java record 关键字**。现有认证模块的 record DTO（`LoginResult`/`RegisterRequest`/`UserView` 等）不在本批次改造范围，留待批次 5 统一替换；新增 `UserView` 相关代码不得新增 record。
- **分层约定**：controller 只做参数校验与返回值处理；业务逻辑全部下沉 service，service 抛 `ApiException(status, message)`；路由层统一捕获（`GlobalExceptionHandler` 返回 `{error:"中文信息"}`）。
- **注释率 ≥20%**，中文注释；重要方法必须有注释说明用途。
- **数据库表每个字段带中文 `COMMENT`**；建表用 `CREATE TABLE IF NOT EXISTS` 幂等，启动自动执行。
- **契约对齐 Express**（逐条）：错误文案、HTTP 状态码、返回 JSON 字段（snake_case）、分页 `{<key>, total, page}`（`page` 从 1 起、`limit` 默认 20），必须与下方各 Task 中列明的契约**完全一致**。
- **DTO 序列化约定（全模块统一，不配全局命名策略）**：响应 DTO 的字段名**直接写 snake_case**（如 `PostView.user_id`，与现有 `UserView.avatar_url` 一致）；请求 DTO 的字段写 camelCase，对前端 snake_case 字段加 `@JsonProperty("snake_case")` 映射（如 `ProfileUpdateRequest.avatarUrl` + `@JsonProperty("avatar_url")`）。不修改 `application.yaml` 的 Jackson 全局策略，避免影响现有 auth 模块。
- **测试**：连真实 MySQL，`DB_PASS='chen9911.' ./mvnw test` 运行；测试数据前缀清理（`@AfterEach` 按测试用户删除关联数据）。
- **公开接口认证（注解方案）**：公开只读 GET 用 `@PublicEndpoint` 注解（Task 2 创建 `annotation/PublicEndpoint.java`），`JwtAuthInterceptor` 命中即放行；需登录接口不加注解。静态资源（`/api/files/**`、`/uploads/**`）由 `if (!(handler instanceof HandlerMethod)) return true;` 天然放行，无需注解。
- **测试文件位置与被测文件同包**：模块集成测试放 `src/test/java/caigou/caigoupetservice/controller/`（镜像被测 Controller 包）；`SchemaSmokeTest` 属基础设施级测试，保留在根包。
- 密码/密钥不入配置库（沿用 `${DB_PASS:}` 环境变量）。
- 所有跨 Task 引用的类/方法签名以「Interfaces」块为准，不得自行改名。

---

## Task 1: POC — 验证 netty-socketio 与 socket.io-client v4 连通性

**Files:**
- Modify: `pom.xml`（加 netty-socketio 依赖）
- Create: `src/main/java/caigou/caigoupetservice/socket/SocketConfig.java`
- Create: `socket-poc/package.json`
- Create: `socket-poc/test-socket.js`
- Create: `socket-poc/README.md`

**Interfaces:**
- Consumes: `JwtService.parseUserId(String)`（已有，返回 `Long`）
- Produces: `SocketConfig` 中的 `SocketIOServer` bean（端口 3001，批次 2 复用）、POC 验证结论（记录在 `socket-poc/README.md`，决定批次 2 走 netty-socketio 还是降级方案 B）

> **任务目标**：排除 netty-socketio 对 socket.io v4 协议的兼容风险。这一步决定批次 2 的架构走向，**不通过则停止本计划中的 socket 相关工作并降级方案 B**（前端改原生 WebSocket + Spring WebSocket）。

- [ ] **Step 1: pom.xml 添加 netty-socketio 依赖**

在 `pom.xml` 的 `<dependencies>` 末尾（Lombok 依赖之后）添加：

```xml
        <!-- socket 实时层:netty-socketio 提供 socket.io 协议兼容,版本 2.0.13 声称支持 socket.io 4.x(POC 验证) -->
        <dependency>
            <groupId>com.corundumstudio.socketio</groupId>
            <artifactId>netty-socketio</artifactId>
            <version>2.0.13</version>
        </dependency>
```

- [ ] **Step 2: 创建最小 SocketConfig**

创建 `src/main/java/caigou/caigoupetservice/socket/SocketConfig.java`：

```java
package caigou.caigoupetservice.socket;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.listener.ConnectListener;
import com.corundumstudio.socketio.listener.DataListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PreDestroy;
import java.util.Map;

/**
 * socket 服务配置:POC 最小验证——注册 chat:join 与 chat:message 两个事件
 * 端口 3001(环境变量 CAIGOPET_SOCKET_PORT 可覆盖),独立于 REST 端口 3000
 */
@Configuration
public class SocketConfig {

    private SocketIOServer server;

    /** 启动 socket 服务,并注册 POC 用事件处理器 */
    @Bean(destroyMethod = "stop")
    public SocketIOServer socketIOServer() {
        com.corundumstudio.socketio.Configuration config =
                new com.corundumstudio.socketio.Configuration();
        config.setHostname("localhost");
        config.setPort(Integer.parseInt(System.getenv().getOrDefault("CAIGOPET_SOCKET_PORT", "3001")));
        config.setAuthorizationListener(data -> {
            // POC 阶段:暂不校验 token,仅打印;批次 2 换用 SocketAuthListener 真实校验
            System.out.println("[POC] handshake auth keys=" + data.getAuthToken());
            return true;
        });

        server = new SocketIOServer(config);
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
                String senderId = client.getHandshakeData().get("userId") == null
                        ? "unknown" : String.valueOf(client.getHandshakeData().get("userId"));
                server.getRoomOperations("room:" + roomId).sendEvent("chat:message",
                        Map.of("room_id", roomId, "user_id", senderId, "content", payload.get("content")));
            }
        });
        server.start();
        return server;
    }
}
```

- [ ] **Step 3: 创建 POC 测试脚本（node，与前端同版本 socket.io-client）**

创建 `socket-poc/package.json`：

```json
{
  "name": "caigoupet-socket-poc",
  "version": "1.0.0",
  "description": "验证 netty-socketio 与 socket.io-client v4 的连通性",
  "dependencies": {
    "socket.io-client": "4.8.3"
  }
}
```

创建 `socket-poc/test-socket.js`：

```js
// POC 验证脚本:双客户端连接 → 鉴权(空) → 双端 join 同房间 → A 发 chat:message → B 应收到
// 运行:cd socket-poc && npm install && node test-socket.js
const { io } = require('socket.io-client');

const SOCKET_URL = process.env.CAIGOPET_SOCKET_URL || 'http://localhost:3001';
const ROOM = 'room1';

function connectClient(label) {
  const socket = io(SOCKET_URL, {
    transports: ['websocket'], // 强制 websocket,跳过 http 长轮询,验证净 websocket 兼容
    auth: { token: 'poc-token' },
  });
  socket.on('connect', () => console.log(`[${label}] connected id=${socket.id}`));
  socket.on('connect_error', (err) => {
    console.error(`[${label}] connect_error:`, err.message);
    process.exit(1);
  });
  socket.on('disconnect', (r) => console.log(`[${label}] disconnected:`, r));
  return socket;
}

function sleep(ms) { return new Promise((r) => setTimeout(r, ms)); }

async function main() {
  const a = connectClient('A');
  const b = connectClient('B');
  await new Promise((resolve) => { a.on('connect', resolve); });

  // B 监听广播
  b.on('chat:message', (data) => {
    console.log('[B] received chat:message =', JSON.stringify(data));
    if (data.room_id === ROOM && data.content === 'hello-poc') {
      console.log('POC PASS: 房间广播双向收发成功');
      process.exit(0);
    } else {
      console.log('POC FAIL: 内容不匹配');
      process.exit(1);
    }
  });

  // 双端都 join 同房间
  a.emit('chat:join', ROOM);
  b.emit('chat:join', ROOM);
  await sleep(300);

  // A 发消息,B 应收到广播
  a.emit('chat:message', { room_id: ROOM, content: 'hello-poc' });
  await sleep(3000); // 若 3 秒内 B 未收到,脚本卡住则视为失败

  console.log('POC FAIL: 3 秒内未收到广播');
  process.exit(1);
}

main().catch((e) => { console.error('POC ERROR:', e); process.exit(1); });
```

- [ ] **Step 4: 运行 POC 验证**

1. 启动后端：`DB_PASS='chen9911.' ./mvnw spring-boot:run`（等日志出现 socket 服务启动提示，端口 3001）
2. 新开终端：`cd socket-poc && npm install && node test-socket.js`
3. 观察：若输出 `POC PASS: 房间广播双向收发成功` → 验证通过；否则记录失败点（错误信息/栈）

- [ ] **Step 5: 记录 POC 结论**

创建 `socket-poc/README.md`，记录：验证日期、通过/失败、失败的具体错误、对批次 2 的结论（netty-socketio 可用 → 继续原方案；不可用 → 降级方案 B）。

- [ ] **Step 6: 提交**

```bash
git add pom.xml src/main/java/caigou/caigoupetservice/socket/ socket-poc/
git commit -m "feat: POC 验证 netty-socketio 与 socket.io-client v4 连通性"
```

> **执行检查点**：若 POC 失败，暂停并汇报，与用户确认是否降级方案 B。**POC 通过后再进入 Task 2。**

---

## Task 2: 基础设施 — 建 13 张表 + 拦截器公开 GET 放行 + 静态资源 + 上传配置 + PageView

**Files:**
- Modify: `src/main/resources/schema.sql`（追加 13 张表 DDL）
- Modify: `src/main/java/caigou/caigoupetservice/interceptor/JwtAuthInterceptor.java`
- Modify: `src/main/java/caigou/caigoupetservice/config/WebConfig.java`（静态资源映射）
- Modify: `src/main/resources/application.yaml`（multipart + 上传目录配置）
- Create: `src/main/java/caigou/caigoupetservice/annotation/PublicEndpoint.java`（公开接口注解）
- Create: `src/main/java/caigou/caigoupetservice/dto/PageView.java`
- Test: `src/test/java/caigou/caigoupetservice/SchemaSmokeTest.java`

**Interfaces:**
- Consumes: 无（基于已有 users 表与认证模式）
- Produces:
  - 表：posts/comments/likes/favorites/follows/resources/chat_rooms/chat_room_members/messages/pet_states/pet_visit_settings/plugins/plugin_favorites（批次 3-7 使用）
  - `PageView<T>`（`new PageView<T>(rows, total, page)`，getter 方法 `getRows/getTotal/getPage`）
  - `JwtAuthInterceptor` 新增「公开只读 GET 放行」逻辑（GET + 公开路径 → 放行）

- [ ] **Step 1: 写建表 smoke 测试（先失败）**

创建 `src/test/java/caigou/caigoupetservice/SchemaSmokeTest.java`：

```java
package caigou.caigoupetservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 建表 smoke 测试:验证 13 张业务表在启动时已自动创建
 */
@SpringBootTest
class SchemaSmokeTest {

    private static final List<String> EXPECTED_TABLES = List.of(
            "posts", "comments", "likes", "favorites", "follows", "resources",
            "chat_rooms", "chat_room_members", "messages",
            "pet_states", "pet_visit_settings",
            "plugins", "plugin_favorites");

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void allBusinessTablesShouldExist() {
        for (String table : EXPECTED_TABLES) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
                    Integer.class, table);
            assertTrue(count != null && count > 0, "缺少数据表: " + table);
        }
    }
}
```

- [ ] **Step 2: 运行确认失败**

运行：`DB_PASS='chen9911.' ./mvnw test -Dtest=SchemaSmokeTest`
Expected: FAIL（表不存在）

- [ ] **Step 3: schema.sql 追加 13 张表 DDL**

在 `src/main/resources/schema.sql` 末尾（`users` 表之后）追加以下 DDL。**每字段带中文 COMMENT，幂等 IF NOT EXISTS**。时间字段沿用 `TIMESTAMP DEFAULT CURRENT_TIMESTAMP`（`deleted_at` 无默认值）。

```sql
-- ===== 社区模块 =====
CREATE TABLE IF NOT EXISTS posts (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    user_id       BIGINT       NOT NULL COMMENT '作者ID(外键→users.id)',
    title         VARCHAR(200) NULL COMMENT '标题(可空)',
    content       TEXT         NULL COMMENT '正文(长文本)',
    content_type  TINYINT      NOT NULL DEFAULT 0 COMMENT '内容类型:0=纯文本 1=markdown 2=富文本',
    summary       VARCHAR(500) NULL COMMENT '摘要(可空)',
    cover_url     VARCHAR(500) NULL COMMENT '封面图URL(可空)',
    tags          JSON         NULL COMMENT '标签数组',
    status        TINYINT      NOT NULL DEFAULT 0 COMMENT '状态:0=草稿 1=公开 2=删除',
    view_count    INT          NOT NULL DEFAULT 0 COMMENT '浏览数',
    like_count    INT          NOT NULL DEFAULT 0 COMMENT '点赞数',
    comment_count INT          NOT NULL DEFAULT 0 COMMENT '评论数',
    is_top        TINYINT      NOT NULL DEFAULT 0 COMMENT '是否置顶:0=否 1=是',
    deleted_at    TIMESTAMP    NULL COMMENT '软删除时间(可空)',
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_user_status (user_id, status),
    KEY idx_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='帖子表';

CREATE TABLE IF NOT EXISTS comments (
    id         BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    post_id    BIGINT   NOT NULL COMMENT '所属帖子ID(外键→posts.id)',
    user_id    BIGINT   NOT NULL COMMENT '评论者ID(外键→users.id)',
    parent_id  BIGINT   NULL COMMENT '父评论ID(可空,一级评论为空)',
    root_id    BIGINT   NULL COMMENT '根评论ID(可空,挂回复用)',
    content    TEXT     NOT NULL COMMENT '评论内容',
    like_count INT      NOT NULL DEFAULT 0 COMMENT '点赞数',
    status     TINYINT  NOT NULL DEFAULT 1 COMMENT '状态:1=正常 0=删除',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_post_created (post_id, created_at),
    KEY idx_user (user_id),
    KEY idx_root (root_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评论表';

CREATE TABLE IF NOT EXISTS likes (
    id         BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    user_id    BIGINT   NOT NULL COMMENT '点赞者ID',
    post_id    BIGINT   NOT NULL COMMENT '帖子ID',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_post (user_id, post_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='点赞表';

CREATE TABLE IF NOT EXISTS favorites (
    id         BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    user_id    BIGINT   NOT NULL COMMENT '收藏者ID',
    post_id    BIGINT   NOT NULL COMMENT '帖子ID',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_post (user_id, post_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='收藏表';

CREATE TABLE IF NOT EXISTS follows (
    id          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    user_id     BIGINT   NOT NULL COMMENT '被关注者ID',
    follower_id BIGINT   NOT NULL COMMENT '关注者ID',
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_follower (user_id, follower_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='关注表';

CREATE TABLE IF NOT EXISTS resources (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    user_id        BIGINT       NOT NULL COMMENT '上传者ID',
    type           TINYINT      NOT NULL COMMENT '类型:1=图片 2=视频 3=文件 4=音频',
    original_name  VARCHAR(255) NOT NULL COMMENT '原始文件名',
    storage_path   VARCHAR(500) NOT NULL COMMENT '磁盘存储文件名(uuid)',
    url            VARCHAR(500) NOT NULL COMMENT '访问URL(/api/files/文件名)',
    thumbnail_url  VARCHAR(500) NULL COMMENT '缩略图URL(可空)',
    size           BIGINT       NOT NULL DEFAULT 0 COMMENT '文件字节数',
    mime_type      VARCHAR(50)  NULL COMMENT 'MIME类型',
    width          INT          NULL COMMENT '图片宽度(可空)',
    height         INT          NULL COMMENT '图片高度(可空)',
    duration       INT          NULL COMMENT '音视频时长(秒,可空)',
    md5            VARCHAR(32)  NULL COMMENT '文件MD5(去重)',
    status         TINYINT      NOT NULL DEFAULT 1 COMMENT '状态:1=正常 0=删除',
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_user (user_id),
    KEY idx_type (type),
    KEY idx_md5 (md5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='资源表';

-- ===== 聊天模块 =====
CREATE TABLE IF NOT EXISTS chat_rooms (
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    type       TINYINT      NOT NULL COMMENT '类型:1=私聊 2=群聊',
    name       VARCHAR(100) NULL COMMENT '聊天室名称(群聊用)',
    avatar_url VARCHAR(500) NULL COMMENT '头像URL(可空)',
    created_by BIGINT       NOT NULL COMMENT '创建者ID',
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='聊天室表';

CREATE TABLE IF NOT EXISTS chat_room_members (
    id              BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    room_id         BIGINT   NOT NULL COMMENT '聊天室ID',
    user_id         BIGINT   NOT NULL COMMENT '成员ID',
    role            TINYINT  NOT NULL DEFAULT 0 COMMENT '角色:2=创建者 0=成员',
    last_read_msg_id BIGINT  NOT NULL DEFAULT 0 COMMENT '最后已读消息ID',
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_room_user (room_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='聊天室成员表';

CREATE TABLE IF NOT EXISTS messages (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    room_id        BIGINT       NOT NULL COMMENT '聊天室ID',
    sender_id      BIGINT       NOT NULL COMMENT '发送者ID',
    msg_type       TINYINT      NOT NULL DEFAULT 0 COMMENT '消息类型:0=文本 1=图 2=视频 3=文件 4=音频 5=系统',
    content        TEXT         NULL COMMENT '消息内容(msg_type=5 为JSON字符串)',
    resource_id    BIGINT       NULL COMMENT '资源ID(可空)',
    reply_to       BIGINT       NULL COMMENT '回复的消息ID(可空)',
    status         TINYINT      NOT NULL DEFAULT 1 COMMENT '状态:1=正常 0=撤回 -1=删除',
    client_msg_id  VARCHAR(64)  NULL COMMENT '客户端消息ID(幂等去重)',
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sender_client (sender_id, client_msg_id),
    KEY idx_room_created (room_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='消息表';

-- ===== 宠物模块 =====
CREATE TABLE IF NOT EXISTS pet_states (
    id            BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    user_id       BIGINT   NOT NULL COMMENT '用户ID(每用户一条)',
    emotion_state JSON     NULL COMMENT '情绪状态对象',
    personality   JSON     NULL COMMENT '性格对象',
    last_sync_at  TIMESTAMP NULL COMMENT '最后同步时间',
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='宠物状态表';

CREATE TABLE IF NOT EXISTS pet_visit_settings (
    id         BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    user_id    BIGINT   NOT NULL COMMENT '用户ID',
    room_id    BIGINT   NULL COMMENT '聊天室ID(空=全局设置)',
    allow      TINYINT  NOT NULL DEFAULT 1 COMMENT '是否允许串门:1=允许 0=拒绝',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_room (user_id, room_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='宠物串门设置表';

-- ===== 插件模块 =====
CREATE TABLE IF NOT EXISTS plugins (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    name            VARCHAR(100) NOT NULL COMMENT '插件名称',
    version         VARCHAR(20)  NOT NULL DEFAULT '1.0.0' COMMENT '版本号',
    description     TEXT         NULL COMMENT '插件描述',
    author_id       BIGINT       NOT NULL COMMENT '作者ID',
    category        VARCHAR(50)  NOT NULL DEFAULT 'tool' COMMENT '分类',
    tags            VARCHAR(500) NULL COMMENT '标签(逗号分隔)',
    icon            VARCHAR(500) NULL COMMENT '图标URL',
    download_count  INT          NOT NULL DEFAULT 0 COMMENT '下载数',
    favorite_count  INT          NOT NULL DEFAULT 0 COMMENT '收藏数',
    manifest_json   TEXT         NULL COMMENT '插件清单JSON',
    file_path       VARCHAR(500) NULL COMMENT '插件文件路径',
    file_size       INT          NOT NULL DEFAULT 0 COMMENT '文件字节数',
    status          TINYINT      NOT NULL DEFAULT 1 COMMENT '状态:0=待审 1=通过 2=拒绝',
    review_comment  VARCHAR(500) NULL COMMENT '审核意见',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_category (category),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='插件表';

CREATE TABLE IF NOT EXISTS plugin_favorites (
    id         BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    user_id    BIGINT   NOT NULL COMMENT '用户ID',
    plugin_id  BIGINT   NOT NULL COMMENT '插件ID',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_plugin (user_id, plugin_id),
    KEY idx_plugin (plugin_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='插件收藏表';
```

- [ ] **Step 4: 创建 PageView（通用分页）**

创建 `src/main/java/caigou/caigoupetservice/dto/PageView.java`：

```java
package caigou.caigoupetservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 通用分页视图:序列化为 {rows, total, page}
 * rows 为当前页数据,序列化时字段名由子类/返回对象决定(如 posts/comments/followers)
 */
@Data
@AllArgsConstructor
public class PageView<T> {

    /** 当前页数据 */
    private List<T> rows;

    /** 总记录数 */
    private long total;

    /** 当前页码(从 1 开始) */
    private long page;
}
```

> **说明**：`PageView` 内部字段为 `rows`，各 Controller 返回时用 `Map.of("posts", view.getRows(), "total", view.getTotal(), "page", view.getPage())` 组装，以对齐 Express 的 `{posts,total,page}` 键名。

- [ ] **Step 5: 创建 @PublicEndpoint 注解 + 拦截器按注解放行公开 GET**

创建 `src/main/java/caigou/caigoupetservice/annotation/PublicEndpoint.java`：

```java
package caigou.caigoupetservice.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记公开接口:标注了此注解的请求方法无需认证直接放行
 * 镜像 Express 未挂 authMiddleware 的公开路由语义
 * Retention 需为 RUNTIME,拦截器才能通过反射识别
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PublicEndpoint {
}
```

修改 `src/main/java/caigou/caigoupetservice/interceptor/JwtAuthInterceptor.java`：在 `preHandle` 的 `if (!(handler instanceof HandlerMethod)) return true;` 之后、token 校验之前，插入「GET + 方法标注 @PublicEndpoint 则放行」逻辑，并新增判断方法：

```java
    /** GET + 方法标注 @PublicEndpoint 时放行(公开只读接口) */
    private boolean isPublicGet(HttpServletRequest request, HandlerMethod handler) {
        if (!"GET".equals(request.getMethod())) {
            return false;
        }
        return handler.getMethod().isAnnotationPresent(PublicEndpoint.class);
    }
```

并在 `preHandle` 顶部（HandlerMethod 判断之后）插入：

```java
        // 公开只读 GET(@PublicEndpoint 注解)直接放行,镜像 Express 未加 authMiddleware 的公开路由
        if (isPublicGet(request, (HandlerMethod) handler)) {
            return true;
        }
```

并补 import：`import caigou.caigoupetservice.annotation.PublicEndpoint;`

> **说明**：每个公开 GET 端点在各自 Controller 的 `@GetMapping` 上加 `@PublicEndpoint`（后续 Task 3-7 逐处标注）。`/api/users/search`、`/api/resources` 列表等需登录端点**不加**注解，由拦截器正常校验。不再需要路径白名单。

- [ ] **Step 6: 静态资源映射与上传配置**

修改 `src/main/java/caigou/caigoupetservice/config/WebConfig.java`：新增 `addResourceHandlers` 方法（在现有 `addCorsMappings` 之后）：

```java
    /**
     * 静态资源映射:上传目录经 /api/files/** 与 /uploads/** 提供访问,复刻 Express express.static(upload.dir)
     */
    @Override
    public void addResourceHandlers(org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry registry) {
        String uploadDir = System.getProperty("upload.dir", "./uploads");
        registry.addResourceHandler("/api/files/**").addResourceLocations("file:" + uploadDir + "/");
        registry.addResourceHandler("/uploads/**").addResourceLocations("file:" + uploadDir + "/");
    }
```

> 说明：`addResourceHandler` 的 `file:` 前缀路径如果包含相对路径可能不被解析，Task 3 实现上传时会读取 `application.yaml` 的 `upload.dir` 配置注入该值，此处先用系统属性兜底占位，Task 3 统一改为配置注入。

修改 `src/main/resources/application.yaml`，在 `server.port` 之后追加：

```yaml
# 文件上传:大小限制与存储目录(multipart 字段名与 Express 一致:file)
spring:
  servlet:
    multipart:
      max-file-size: 10MB
      max-request-size: 12MB

upload:
  dir: ${UPLOAD_DIR:./uploads}
```

> **注意**：由于 `application.yaml` 顶层已有 `spring:`，需把 `servlet:` 合并进顶层 `spring:` 节点（不要重复写 `spring:` 顶层键）。Task 3 从 `@Value("${upload.dir}")` 读取目录。

- [ ] **Step 7: 运行测试确认通过**

运行：`DB_PASS='chen9911.' ./mvnw test -Dtest=SchemaSmokeTest`
Expected: PASS（13 张表已创建）

- [ ] **Step 8: 迁移现有 AuthApiIntegrationTest 到 controller/ 包（测试与被测文件同包）**

将 `src/test/java/caigou/caigoupetservice/AuthApiIntegrationTest.java` 移到 `src/test/java/caigou/caigoupetservice/controller/AuthApiIntegrationTest.java`，包名改为 `package caigou.caigoupetservice.controller;`（其被测 `AuthController` 在 `...controller` 包，测试路径须镜像）。

- [ ] **Step 9: 回归认证测试**

运行：`DB_PASS='chen9911.' ./mvnw test -Dtest=AuthApiIntegrationTest`
Expected: 全部通过（拦截器改动未破坏认证）

- [ ] **Step 10: 提交**

```bash
git add src/main/resources/schema.sql src/main/java/caigou/caigoupetservice/annotation/PublicEndpoint.java src/main/java/caigou/caigoupetservice/interceptor/JwtAuthInterceptor.java src/main/java/caigou/caigoupetservice/config/WebConfig.java src/main/resources/application.yaml src/main/java/caigou/caigoupetservice/dto/PageView.java src/test/java/caigou/caigoupetservice/SchemaSmokeTest.java src/test/java/caigou/caigoupetservice/controller/AuthApiIntegrationTest.java
git commit -m "feat: 基础设施——13 张业务表、@PublicEndpoint 注解认证、静态资源与上传配置、通用分页"
```

---

## Task 3: resources 模块（上传 / 列表 / 详情 / 删除）

**Files:**
- Create: `src/main/java/caigou/caigoupetservice/entity/Resource.java`
- Create: `src/main/java/caigou/caigoupetservice/mapper/ResourceMapper.java`
- Create: `src/main/java/caigou/caigoupetservice/dto/ResourceView.java`
- Create: `src/main/java/caigou/caigoupetservice/service/ResourceService.java`
- Create: `src/main/java/caigou/caigoupetservice/controller/ResourceController.java`
- Modify: `src/main/java/caigou/caigoupetservice/config/WebConfig.java`（`addResourceHandlers` 改用配置注入）
- Test: `src/test/java/caigou/caigoupetservice/controller/ResourceApiIntegrationTest.java`

**Interfaces:**
- Consumes: `PageView<T>`（Task 2）、`ApiException`（已有）、`currentUserId` request attribute（已有）、`application.yaml` 的 `upload.dir`
- Produces:
  - `ResourceController` 端点：`POST /api/resources/upload`、`GET /api/resources`、`GET /api/resources/{id}`、`DELETE /api/resources/{id}`、`GET /api/resources/files/{filename}`（复用静态映射，不写 controller）
  - `ResourceView`（id/user_id/type/original_name/storage_path/url/size/mime_type/md5/status/created_at/updated_at）

**契约对齐（Express resources.js，前缀 /api/resources）：**
- `POST /api/resources/upload`（登录，multipart 字段 `file`）：超 10MB→413 `"文件大小超出限制"`；无文件→400 `"请选择文件"`；扩展名不在白名单→400 `"不支持的文件类型: .ext"`；成功 201 `{resource}`（url=`/api/files/{文件名}`）；MD5 去重——命中已有 status=1 记录则删新文件返回旧记录（仍 201）
- `GET /api/resources`（登录）：query `type`、`page=1,limit=20`，status=1 → `{resources, total, page}`
- `GET /api/resources/{id}`（公开）：不存在→404 `"文件不存在"`；成功 `{resource}`
- `DELETE /api/resources/{id}`（登录）：不存在→404 `"文件不存在"`；非本人→403 `"无权删除此文件"`；软删 status=0 → `{message:"删除成功"}`
- 扩展名白名单（Express config）以 `.jpg/.jpeg/.png/.gif/.webp/.mp4/.webm/.pdf/.zip/.mp3` 为准（实现时按 `server/src/config/index.js` 的 `upload.allowedExtensions` 核对）

- [ ] **Step 1: 写集成测试（先失败）**

创建 `src/test/java/caigou/caigoupetservice/controller/ResourceApiIntegrationTest.java`：

```java
package caigou.caigoupetservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * resources 模块集成测试:上传/列表/详情/删除/越权/类型校验/MD5 去重
 */
@SpringBootTest
@AutoConfigureMockMvc
class ResourceApiIntegrationTest {

    private static final String PREFIX = "testres_";
    private static final ObjectMapper OM = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private Long testUserId;

    @AfterEach
    void cleanUp() {
        if (testUserId != null) {
            jdbc.update("DELETE FROM resources WHERE user_id = ?", testUserId);
            jdbc.update("DELETE FROM users WHERE id = ?", testUserId);
        }
    }

    private String register(String username) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/auth/register").contentType("application/json")
                        .content(OM.writeValueAsString(Map.of("username", username, "password", "pass123"))))
                .andExpect(status().isCreated()).andReturn();
        String token = OM.readTree(r.getResponse().getContentAsString()).get("token").asText();
        Long uid = jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
        testUserId = uid;
        return token;
    }

    @Test
    void upload_shouldReturn201WithResource() throws Exception {
        String token = register(PREFIX + "u1");
        MockMultipartFile file = new MockMultipartFile("file", "cat.png", "image/png", new byte[]{1, 2, 3});
        mockMvc.perform(multipart("/api/resources/upload").file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resource.url").value(org.hamcrest.Matchers.startsWith("/api/files/")))
                .andExpect(jsonPath("$.resource.type").value(1));
    }

    @Test
    void upload_unsupportedType_shouldReturn400() throws Exception {
        String token = register(PREFIX + "u2");
        MockMultipartFile file = new MockMultipartFile("file", "evil.exe", "application/octet-stream", new byte[]{1});
        mockMvc.perform(multipart("/api/resources/upload").file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("不支持的文件类型")));
    }

    @Test
    void upload_noFile_shouldReturn400() throws Exception {
        String token = register(PREFIX + "u3");
        mockMvc.perform(multipart("/api/resources/upload").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("请选择文件"));
    }

    @Test
    void upload_sameMd5_shouldDeduplicate() throws Exception {
        String token = register(PREFIX + "u4");
        byte[] bytes = {9, 9, 9};
        MockMultipartFile f1 = new MockMultipartFile("file", "a.png", "image/png", bytes);
        MockMultipartFile f2 = new MockMultipartFile("file", "b.png", "image/png", bytes);
        mockMvc.perform(multipart("/api/resources/upload").file(f1).header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated());
        // 相同 MD5 二次上传:返回旧记录(仍 201),资源行数不增加
        mockMvc.perform(multipart("/api/resources/upload").file(f2).header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated());
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM resources WHERE user_id = ?", Integer.class, testUserId);
        org.junit.jupiter.api.Assertions.assertEquals(1, count, "MD5 去重后不应新增记录");
    }

    @Test
    void delete_notOwner_shouldReturn403() throws Exception {
        String owner = register(PREFIX + "own");
        MockMultipartFile file = new MockMultipartFile("file", "c.png", "image/png", new byte[]{5});
        MvcResult up = mockMvc.perform(multipart("/api/resources/upload").file(file)
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isCreated()).andReturn();
        long rid = OM.readTree(up.getResponse().getContentAsString()).get("resource").get("id").asLong();

        String other = register(PREFIX + "oth"); // 复用 testUserId,覆盖 owner 关联清理
        jdbc.update("DELETE FROM resources WHERE user_id = ?", testUserId);
        testUserId = null; // 保留 owner 行由后续清理
        mockMvc.perform(delete("/api/resources/" + rid).header("Authorization", "Bearer " + other))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("无权删除此文件"));
    }

    @Test
    void delete_owner_shouldSoftDelete() throws Exception {
        String token = register(PREFIX + "del");
        MockMultipartFile file = new MockMultipartFile("file", "d.png", "image/png", new byte[]{7});
        MvcResult up = mockMvc.perform(multipart("/api/resources/upload").file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated()).andReturn();
        long rid = OM.readTree(up.getResponse().getContentAsString()).get("resource").get("id").asLong();
        mockMvc.perform(delete("/api/resources/" + rid).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("删除成功"));
    }
}
```

> **测试隔离提示**：`register()` 每次调用会覆盖 `testUserId`，清理时只删最近用户。`delete_notOwner` 用临时手段绕过（先删 other 的关联再清 testUserId），如实现不便可改为在 `cleanUp` 里按 `username LIKE 'testres_%'` 关联清理。**以能稳定重复运行为准**。

- [ ] **Step 2: 运行确认失败**

运行：`DB_PASS='chen9911.' ./mvnw test -Dtest=ResourceApiIntegrationTest`
Expected: FAIL（controller/service 不存在，编译失败）

- [ ] **Step 3: 创建实体与 Mapper**

创建 `src/main/java/caigou/caigoupetservice/entity/Resource.java`（Lombok `@Data`，字段对应 resources 表，含 `storagePath/thumbnailUrl/mimeType/createdAt/updatedAt` 驼峰映射）：

```java
package caigou.caigoupetservice.entity;

import lombok.Data;

/**
 * 资源实体,对应 resources 表(上传文件元数据)
 * 数据库下划线字段经 mybatis map-underscore-to-camel-case 自动映射为驼峰属性
 */
@Data
public class Resource {

    /** 主键(自增) */
    private Long id;
    /** 上传者ID */
    private Long userId;
    /** 类型:1=图片 2=视频 3=文件 4=音频 */
    private Integer type;
    /** 原始文件名 */
    private String originalName;
    /** 磁盘存储文件名(uuid) */
    private String storagePath;
    /** 访问URL */
    private String url;
    /** 缩略图URL */
    private String thumbnailUrl;
    /** 文件字节数 */
    private Long size;
    /** MIME类型 */
    private String mimeType;
    /** 图片宽度 */
    private Integer width;
    /** 图片高度 */
    private Integer height;
    /** 音视频时长(秒) */
    private Integer duration;
    /** 文件MD5(去重) */
    private String md5;
    /** 状态:1=正常 0=删除 */
    private Integer status;
    /** 创建时间(DB 维护) */
    private String createdAt;
    /** 更新时间(DB 维护) */
    private String updatedAt;
}
```

创建 `src/main/java/caigou/caigoupetservice/mapper/ResourceMapper.java`：

```java
package caigou.caigoupetservice.mapper;

import caigou.caigoupetservice.entity.Resource;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 资源数据访问接口:上传记录增删查与 MD5 去重
 */
@Mapper
public interface ResourceMapper {

    /** 按主键查询资源(不含已删除) */
    @Select("SELECT * FROM resources WHERE id = #{id} AND status = 1")
    Resource findById(@Param("id") Long id);

    /** 按 MD5 查询已存在资源(去重用,仅活跃记录) */
    @Select("SELECT * FROM resources WHERE md5 = #{md5} AND status = 1 LIMIT 1")
    Resource findByMd5(@Param("md5") String md5);

    /** 插入资源记录,回填自增主键 */
    @Insert("INSERT INTO resources (user_id, type, original_name, storage_path, url, thumbnail_url, size, mime_type, width, height, duration, md5) " +
            "VALUES (#{userId}, #{type}, #{originalName}, #{storagePath}, #{url}, #{thumbnailUrl}, #{size}, #{mimeType}, #{width}, #{height}, #{duration}, #{md5})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Resource resource);

    /** 按用户与类型分页查询活跃资源 */
    @Select("SELECT * FROM resources WHERE user_id = #{userId} AND status = 1 " +
            "<script><if test='type != null'> AND type = #{type}</if></script> " +
            "ORDER BY created_at DESC LIMIT #{offset}, #{limit}</script>")
    List<Resource> listByUser(@Param("userId") Long userId, @Param("type") Integer type,
                              @Param("offset") int offset, @Param("limit") int limit);

    /** 统计用户活跃资源总数 */
    @Select("SELECT COUNT(*) FROM resources WHERE user_id = #{userId} AND status = 1 " +
            "<script><if test='type != null'> AND type = #{type}</if></script></script>")
    long countByUser(@Param("userId") Long userId, @Param("type") Integer type);

    /** 软删除资源 */
    @Update("UPDATE resources SET status = 0 WHERE id = #{id}")
    int softDelete(@Param("id") Long id);
}
```

> **注意**：MyBatis 注解里的 `<script>` 动态 SQL 需要正确的开启/闭合标签。上面 `listByUser`/`countByUser` 中的 `<script>` 包裹 `<if>`，实际使用时如遇解析问题，可改为「固定 SQL + 若 type 为 null 传 -1」的简单写法（`WHERE type = #{type}` 传 `-1` 时无匹配）。**以实现可编译可运行为准**，不必强求动态 SQL。

- [ ] **Step 4: 创建 ResourceView**

创建 `src/main/java/caigou/caigoupetservice/dto/ResourceView.java`（Lombok `@Data` + `@AllArgsConstructor`，含静态 `from(Resource)`）：

```java
package caigou.caigoupetservice.dto;

import caigou.caigoupetservice.entity.Resource;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 资源视图:返回给前端的资源信息,字段名与 Express 响应一致(snake_case)
 */
@Data
@AllArgsConstructor
public class ResourceView {

    /** 主键ID */
    private Long id;
    /** 上传者ID */
    private Long user_id;
    /** 类型:1=图片 2=视频 3=文件 4=音频 */
    private Integer type;
    /** 原始文件名 */
    private String original_name;
    /** 磁盘存储文件名 */
    private String storage_path;
    /** 访问URL */
    private String url;
    /** 文件字节数 */
    private Long size;
    /** MIME类型 */
    private String mime_type;
    /** 文件MD5 */
    private String md5;
    /** 状态 */
    private Integer status;
    /** 创建时间 */
    private String created_at;
    /** 更新时间 */
    private String updated_at;

    /** 从实体构造视图 */
    public static ResourceView from(Resource r) {
        return new ResourceView(r.getId(), r.getUserId(), r.getType(), r.getOriginalName(),
                r.getStoragePath(), r.getUrl(), r.getSize(), r.getMimeType(), r.getMd5(),
                r.getStatus(), r.getCreatedAt(), r.getUpdatedAt());
    }
}
```

- [ ] **Step 5: 创建 ResourceService**

创建 `src/main/java/caigou/caigoupetservice/service/ResourceService.java`：

```java
package caigou.caigoupetservice.service;

import caigou.caigoupetservice.dto.PageView;
import caigou.caigoupetservice.dto.ResourceView;
import caigou.caigoupetservice.entity.Resource;
import caigou.caigoupetservice.exception.ApiException;
import caigou.caigoupetservice.mapper.ResourceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 资源业务:文件上传(类型校验/MD5 去重/落盘)/列表/详情/软删除
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceService {

    private static final long MAX_SIZE = 10L * 1024 * 1024;
    /** 允许上传的扩展名(对齐 Express config 白名单) */
    private static final Set<String> ALLOWED_EXT = Set.of(
            "jpg", "jpeg", "png", "gif", "webp", "mp4", "webm", "pdf", "zip", "mp3");

    private final ResourceMapper resourceMapper;

    /** 上传目录(来自 application.yaml upload.dir) */
    @Value("${upload.dir:./uploads}")
    private String uploadDir;

    /**
     * 上传文件:校验大小与类型 → 计算 MD5 去重 → 落盘 → 入库
     */
    public ResourceView upload(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(400, "请选择文件");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new ApiException(413, "文件大小超出限制");
        }
        String original = file.getOriginalFilename();
        String ext = original == null ? "" : original.substring(original.lastIndexOf('.') + 1).toLowerCase();
        if (!ALLOWED_EXT.contains(ext)) {
            throw new ApiException(400, "不支持的文件类型: ." + ext);
        }
        String md5 = md5Hex(file);
        // MD5 去重:命中已有活跃记录则返回旧记录
        Resource existing = resourceMapper.findByMd5(md5);
        if (existing != null) {
            return ResourceView.from(existing);
        }
        String storageName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        try {
            Path dir = Paths.get(uploadDir);
            Files.createDirectories(dir);
            file.transferTo(dir.resolve(storageName).toAbsolutePath());
        } catch (IOException e) {
            log.error("文件落盘失败", e);
            throw new ApiException(500, "上传失败");
        }
        Resource resource = new Resource();
        resource.setUserId(userId);
        resource.setType(inferType(ext));
        resource.setOriginalName(original);
        resource.setStoragePath(storageName);
        resource.setUrl("/api/files/" + storageName);
        resource.setSize(file.getSize());
        resource.setMimeType(file.getContentType());
        resource.setMd5(md5);
        resource.setStatus(1);
        resourceMapper.insert(resource);
        return ResourceView.from(resource);
    }

    /** 分页查询当前用户资源 */
    public PageView<ResourceView> list(Long userId, Integer type, int page, int limit) {
        int offset = (page - 1) * limit;
        List<ResourceView> rows = resourceMapper.listByUser(userId, type, offset, limit)
                .stream().map(ResourceView::from).toList();
        long total = resourceMapper.countByUser(userId, type);
        return new PageView<>(rows, total, page);
    }

    /** 查询单个资源详情 */
    public ResourceView detail(Long id) {
        Resource r = resourceMapper.findById(id);
        if (r == null) {
            throw new ApiException(404, "文件不存在");
        }
        return ResourceView.from(r);
    }

    /** 软删除资源(仅本人可删) */
    public void delete(Long id, Long userId) {
        Resource r = resourceMapper.findById(id);
        if (r == null) {
            throw new ApiException(404, "文件不存在");
        }
        if (!r.getUserId().equals(userId)) {
            throw new ApiException(403, "无权删除此文件");
        }
        resourceMapper.softDelete(id);
    }

    /** 根据扩展名推断资源类型:1图 2视频 3文件 4音频 */
    private int inferType(String ext) {
        if (Set.of("jpg", "jpeg", "png", "gif", "webp").contains(ext)) return 1;
        if (Set.of("mp4", "webm").contains(ext)) return 2;
        if (Set.of("mp3").contains(ext)) return 4;
        return 3;
    }

    /** 计算文件 MD5 十六进制 */
    private String md5Hex(MultipartFile file) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(file.getBytes());
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new ApiException(500, "上传失败");
        }
    }
}
```

- [ ] **Step 6: 创建 ResourceController**

创建 `src/main/java/caigou/caigoupetservice/controller/ResourceController.java`：

```java
package caigou.caigoupetservice.controller;

import caigou.caigoupetservice.annotation.PublicEndpoint;
import caigou.caigoupetservice.dto.PageView;
import caigou.caigoupetservice.dto.ResourceView;
import caigou.caigoupetservice.service.ResourceService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 资源控制器:仅做参数接收与返回组装,业务在 service 层
 */
@RestController
@RequestMapping("/api/resources")
@RequiredArgsConstructor
public class ResourceController {

    private final ResourceService resourceService;

    /** 上传文件:成功 201 + {resource} */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, ResourceView>> upload(HttpServletRequest request,
                                                            @RequestParam("file") MultipartFile file) {
        Long userId = (Long) request.getAttribute("currentUserId");
        return ResponseEntity.status(201).body(Map.of("resource", resourceService.upload(userId, file)));
    }

    /** 资源列表(需登录) */
    @GetMapping
    public Map<String, Object> list(HttpServletRequest request,
                                    @RequestParam(required = false) Integer type,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int limit) {
        Long userId = (Long) request.getAttribute("currentUserId");
        PageView<ResourceView> view = resourceService.list(userId, type, page, limit);
        return Map.of("resources", view.getRows(), "total", view.getTotal(), "page", view.getPage());
    }

    /** 资源详情(公开) */
    @PublicEndpoint
    @GetMapping("/{id}")
    public Map<String, ResourceView> detail(@PathVariable Long id) {
        return Map.of("resource", resourceService.detail(id));
    }

    /** 删除资源(本人) */
    @DeleteMapping("/{id}")
    public Map<String, String> delete(@PathVariable Long id, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("currentUserId");
        resourceService.delete(id, userId);
        return Map.of("message", "删除成功");
    }
}
```

> 静态文件访问 `GET /api/resources/files/{filename}`：由 Task 2 的 `/api/files/**` 映射提供，无需 controller。若前端实际用 `/api/resources/files/{filename}` 路径访问，则需在 `WebConfig.addResourceHandlers` 追加 `/api/resources/files/**` 映射（实现时核对前端 `contacts.js`/`chatlist.js` 的图片 URL 写法）。

- [ ] **Step 7: WebConfig 上传目录改为配置注入**

修改 `WebConfig.addResourceHandlers`：注入 `@Value("${upload.dir:./uploads}")` 字段并用它替换 `System.getProperty("upload.dir", "./uploads")`。

- [ ] **Step 8: 运行测试确认通过**

运行：`DB_PASS='chen9911.' ./mvnw test -Dtest=ResourceApiIntegrationTest`
Expected: PASS

- [ ] **Step 9: 提交**

```bash
git add src/main/java/caigou/caigoupetservice/entity/Resource.java src/main/java/caigou/caigoupetservice/mapper/ResourceMapper.java src/main/java/caigou/caigoupetservice/dto/ResourceView.java src/main/java/caigou/caigoupetservice/service/ResourceService.java src/main/java/caigou/caigoupetservice/controller/ResourceController.java src/main/java/caigou/caigoupetservice/config/WebConfig.java src/test/java/caigou/caigoupetservice/controller/ResourceApiIntegrationTest.java
git commit -m "feat: resources 模块——上传(MD5去重/类型校验)、列表、详情、软删除"
```

---

## Task 4: users 模块扩展（search / profile / 详情 / 用户帖子）

**Files:**
- Modify: `src/main/java/caigou/caigoupetservice/mapper/UserMapper.java`（追加 search/update/帖子分页）
- Create: `src/main/java/caigou/caigoupetservice/dto/ProfileUpdateRequest.java`
- Create: `src/main/java/caigou/caigoupetservice/dto/UserSearchView.java`
- Create: `src/main/java/caigou/caigoupetservice/service/UserService.java`
- Create: `src/main/java/caigou/caigoupetservice/controller/UserController.java`
- Test: `src/test/java/caigou/caigoupetservice/controller/UserApiIntegrationTest.java`

**Interfaces:**
- Consumes: `PageView<T>`（Task 2）、`ApiException`、`currentUserId`、`UserMapper`（已有 `findById`）、`UserView`（已有，注意是 record，仅读取复用）
- Produces:
  - `UserController` 端点：`GET /api/users/search`、`GET /api/users/{id}`、`PUT /api/users/profile`、`GET /api/users/{id}/posts`
  - `UserService` 方法：`search(String q, Long selfId)`、`getById(Long id)`、`updateProfile(Long id, ProfileUpdateRequest)`、`userPosts(Long userId, int page, int limit)`
  - `ProfileUpdateRequest`（Lombok `@Data`：nickname/avatar_url/email/gender/bio）、`UserSearchView`（Lombok `@Data`：id/username/nickname/avatar_url/gender）

**契约对齐（Express users.js，前缀 /api/users）：**
- `GET /api/users/search`（**需登录**）：query `q`（必填，空串返回 `{users:[]}`）；排除自己，username/nickname LIKE `%q%`，限 20 → `{users:[{id,username,nickname,avatar_url,gender}]}`
- `GET /api/users/{id}`（公开）：404 `"用户不存在"` → `{user:{id,username,nickname,avatar_url,email,gender,bio,ip,province,city,following_count,followers_count,likes_count,favorites_count,created_at}}`
- `PUT /api/users/profile`（需登录）：body nickname/avatar_url/email/gender/bio（仅更新传入非空字段）→ `{user:{同上}}`
- `GET /api/users/{id}/posts`（公开）：query `page=1,limit=20`，where user_id+status=1，order created_at DESC → `{posts:[Post 含 author user 内嵌], total, page}`（无 limit 回显）

> **说明**：`userPosts` 需要 Post 实体与作者内嵌。Post 实体在 Task 5 创建。为避免循环依赖，本 Task 先实现 `search/getById/updateProfile` 三个端点（不依赖 Post），`userPosts` 端点放到 Task 5（posts 模块）一并实现。本 Task 测试覆盖 search/详情/profile。

- [ ] **Step 1: 写集成测试（先失败）**

创建 `src/test/java/caigou/caigoupetservice/controller/UserApiIntegrationTest.java`，覆盖：search（匹配昵称/排除自己/空 q 返回空）、详情（存在/404）、profile 更新（改昵称后返回新值）：

```java
package caigou.caigoupetservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * users 模块集成测试:搜索/详情/资料更新
 */
@SpringBootTest
@AutoConfigureMockMvc
class UserApiIntegrationTest {

    private static final String PREFIX = "testusr_";
    private static final ObjectMapper OM = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM users WHERE username LIKE '" + PREFIX + "%'");
    }

    private String register(String username) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/auth/register").contentType("application/json")
                        .content(OM.writeValueAsString(Map.of("username", username, "password", "pass123", "nickname", "Nick" + username))))
                .andExpect(status().isCreated()).andReturn();
        return OM.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void search_byNickname_shouldReturnUsers() throws Exception {
        String token = register(PREFIX + "search1");
        register(PREFIX + "search2");
        mockMvc.perform(get("/api/users/search").param("q", "Nick").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users").isArray())
                .andExpect(jsonPath("$.users[0].username").isNotEmpty());
    }

    @Test
    void search_emptyQ_shouldReturnEmpty() throws Exception {
        String token = register(PREFIX + "search3");
        mockMvc.perform(get("/api/users/search").param("q", "").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users").isArray())
                .andExpect(jsonPath("$.users.length()").value(0));
    }

    @Test
    void getById_existing_shouldReturnUser() throws Exception {
        register(PREFIX + "detail");
        Long uid = jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, PREFIX + "detail");
        mockMvc.perform(get("/api/users/" + uid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.username").value(PREFIX + "detail"))
                .andExpect(jsonPath("$.user.following_count").value(0));
    }

    @Test
    void getById_missing_shouldReturn404() throws Exception {
        mockMvc.perform(get("/api/users/999999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("用户不存在"));
    }

    @Test
    void updateProfile_shouldReturnNewValues() throws Exception {
        String token = register(PREFIX + "prof");
        mockMvc.perform(put("/api/users/profile").header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(OM.writeValueAsString(Map.of("nickname", "新昵称", "bio", "你好世界"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.nickname").value("新昵称"))
                .andExpect(jsonPath("$.user.bio").value("你好世界"));
    }
}
```

- [ ] **Step 2: 运行确认失败**

运行：`DB_PASS='chen9911.' ./mvnw test -Dtest=UserApiIntegrationTest`
Expected: FAIL（编译失败）

- [ ] **Step 3: UserMapper 追加方法**

在 `UserMapper.java` 追加（复用已有的 `@Select`/`@Update` 风格）：

```java
    /** 关键字搜索用户(排除自己,匹配用户名或昵称,限 20) */
    @Select("SELECT * FROM users WHERE status = 1 AND id != #{selfId} " +
            "AND (username LIKE CONCAT('%', #{q}, '%') OR nickname LIKE CONCAT('%', #{q}, '%')) " +
            "ORDER BY id DESC LIMIT 20")
    List<User> search(@Param("q") String q, @Param("selfId") Long selfId);

    /** 更新用户资料(仅更新传入的非空字段) */
    @Update("<script>UPDATE users SET updated_at = CURRENT_TIMESTAMP" +
            "<if test='nickname != null'> , nickname = #{nickname}</if>" +
            "<if test='avatarUrl != null'> , avatar_url = #{avatarUrl}</if>" +
            "<if test='email != null'> , email = #{email}</if>" +
            "<if test='gender != null'> , gender = #{gender}</if>" +
            "<if test='bio != null'> , bio = #{bio}</if>" +
            " WHERE id = #{id}</script>")
    int updateProfile(User user);
```

并在文件顶部补 `import java.util.List;`。

- [ ] **Step 4: 创建 ProfileUpdateRequest**

创建 `src/main/java/caigou/caigoupetservice/dto/ProfileUpdateRequest.java`（Lombok `@Data`，字段名用 camelCase，与 JSON body 的 snake_case 字段需显式映射——见 Step 5 的 `@JsonProperty` 说明；此处字段定义为：nickname/avatarUrl/email/gender/bio）：

```java
package caigou.caigoupetservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 用户资料更新请求体
 * 字段仅更新传入的非空值,未传字段保持不变
 */
@Data
public class ProfileUpdateRequest {

    /** 昵称 */
    private String nickname;
    /** 头像地址(请求体字段为 avatar_url) */
    @JsonProperty("avatar_url")
    private String avatarUrl;
    /** 邮箱 */
    private String email;
    /** 性别 */
    private String gender;
    /** 个人简介 */
    private String bio;
}
```

> **序列化说明**：前端 PUT body 用 snake_case（`avatar_url`）。按 Global Constraints 的 DTO 约定——请求字段写 camelCase 并用 `@JsonProperty` 映射 snake_case，响应 DTO 字段直接写 snake_case。本 Task 的 `UserService` 读取 `req.getAvatarUrl()` 即可。

- [ ] **Step 5: 创建 UserSearchView**

创建 `src/main/java/caigou/caigoupetservice/dto/UserSearchView.java`（Lombok `@Data` + `@AllArgsConstructor` + 静态 `from`）：字段 `id/username/nickname/avatar_url/gender`。

- [ ] **Step 6: 创建 UserService**

创建 `src/main/java/caigou/caigoupetservice/service/UserService.java`：

```java
package caigou.caigoupetservice.service;

import caigou.caigoupetservice.dto.ProfileUpdateRequest;
import caigou.caigoupetservice.dto.UserSearchView;
import caigou.caigoupetservice.entity.User;
import caigou.caigoupetservice.exception.ApiException;
import caigou.caigoupetservice.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 用户业务:搜索/详情/资料更新
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;

    /** 关键字搜索用户(排除自己) */
    public List<UserSearchView> search(String q, Long selfId) {
        if (q == null || q.isBlank()) {
            return List.of();
        }
        return userMapper.search(q.trim(), selfId).stream().map(UserSearchView::from).toList();
    }

    /** 用户详情视图(返回给前端的完整公开字段) */
    public Map<String, Object> getProfile(Long id) {
        User user = userMapper.findById(id);
        if (user == null) {
            throw new ApiException(404, "用户不存在");
        }
        return Map.of(
                "id", user.getId(),
                "username", user.getUsername(),
                "nickname", user.getNickname(),
                "avatar_url", user.getAvatarUrl(),
                "email", user.getEmail(),
                "gender", user.getGender(),
                "bio", user.getBio(),
                "ip", user.getIp(),
                "province", user.getProvince(),
                "city", user.getCity(),
                "following_count", user.getFollowingCount(),
                "followers_count", user.getFollowersCount(),
                "likes_count", user.getLikesCount(),
                "favorites_count", user.getFavoritesCount(),
                "created_at", user.getCreatedAt());
    }

    /** 更新资料并返回最新用户详情 */
    public Map<String, Object> updateProfile(Long userId, ProfileUpdateRequest req) {
        User user = new User();
        user.setId(userId);
        user.setNickname(req.getNickname());
        user.setAvatarUrl(req.getAvatarUrl());
        user.setEmail(req.getEmail());
        user.setGender(req.getGender());
        user.setBio(req.getBio());
        userMapper.updateProfile(user);
        return getProfile(userId);
    }
}
```

- [ ] **Step 7: 创建 UserController**

创建 `src/main/java/caigou/caigoupetservice/controller/UserController.java`：

```java
package caigou.caigoupetservice.controller;

import caigou.caigoupetservice.annotation.PublicEndpoint;
import caigou.caigoupetservice.dto.ProfileUpdateRequest;
import caigou.caigoupetservice.dto.UserSearchView;
import caigou.caigoupetservice.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 用户控制器:搜索/详情/资料更新(业务在 service)
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /** 搜索用户(需登录) */
    @GetMapping("/search")
    public Map<String, List<UserSearchView>> search(@RequestParam(required = false) String q,
                                                    HttpServletRequest request) {
        Long selfId = (Long) request.getAttribute("currentUserId");
        return Map.of("users", userService.search(q, selfId));
    }

    /** 用户详情(公开) */
    @PublicEndpoint
    @GetMapping("/{id}")
    public Map<String, Object> getById(@PathVariable Long id) {
        return Map.of("user", userService.getProfile(id));
    }

    /** 更新资料(需登录) */
    @PutMapping("/profile")
    public Map<String, Object> updateProfile(@RequestBody ProfileUpdateRequest req,
                                             HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("currentUserId");
        return Map.of("user", userService.updateProfile(userId, req));
    }
}
```

> `GET /api/users/{id}/posts` 在 Task 5 实现（依赖 Post 实体）。`GET /api/users/search` 需登录——已在 Task 2 的 `isPublicGet` 中显式排除，拦截器会正常校验。

- [ ] **Step 8: 运行测试确认通过**

运行：`DB_PASS='chen9911.' ./mvnw test -Dtest=UserApiIntegrationTest`
Expected: PASS

- [ ] **Step 9: 提交**

```bash
git add src/main/java/caigou/caigoupetservice/mapper/UserMapper.java src/main/java/caigou/caigoupetservice/dto/ProfileUpdateRequest.java src/main/java/caigou/caigoupetservice/dto/UserSearchView.java src/main/java/caigou/caigoupetservice/service/UserService.java src/main/java/caigou/caigoupetservice/controller/UserController.java src/test/java/caigou/caigoupetservice/controller/UserApiIntegrationTest.java
git commit -m "feat: users 模块——搜索、详情、资料更新"
```

---

## Task 5: posts 模块（CRUD + 列表 + 用户帖子）

**Files:**
- Create: `src/main/java/caigou/caigoupetservice/entity/Post.java`
- Create: `src/main/java/caigou/caigoupetservice/mapper/PostMapper.java`
- Create: `src/main/java/caigou/caigoupetservice/dto/PostCreateRequest.java`
- Create: `src/main/java/caigou/caigoupetservice/dto/PostView.java`
- Create: `src/main/java/caigou/caigoupetservice/service/PostService.java`
- Create: `src/main/java/caigou/caigoupetservice/controller/PostController.java`
- Modify: `src/main/java/caigou/caigoupetservice/controller/UserController.java`（追加 `GET /api/users/{id}/posts`，注入 `PostService`）
- Test: `src/test/java/caigou/caigoupetservice/controller/PostApiIntegrationTest.java`

**Interfaces:**
- Consumes: `PageView<T>`、`ApiException`、`currentUserId`、`UserView`（作者内嵌）、`UserMapper.findById`（作者信息）
- Produces:
  - `PostView`（Lombok `@Data`：id/user_id/title/content/content_type/summary/cover_url/tags(List<String>)/status/view_count/like_count/comment_count/is_top/created_at/updated_at + `user`(UserView)）
  - `PostMapper` 方法：`insert/selectById/selectVisibleById(status=1)/listFeed(offset,limit)/countFeed/update/softDelete/listByUser(userId,offset,limit)/countByUser`
  - `PostController` 端点：`POST /api/posts`、`GET /api/posts`、`GET /api/posts/{id}`、`PUT /api/posts/{id}`、`DELETE /api/posts/{id}`
  - `UserController` 追加：`GET /api/users/{id}/posts`

**契约对齐（Express posts.js，前缀 /api/posts）：**
- `POST /api/posts`（登录）：body title/content(必填)/content_type(默认0)/summary/cover_url/tags(默认[])；content 为空→400 `"内容不能为空"`；成功 201 `{post:{..., user:{id,username,nickname,avatar_url}}}`；status 固定 1
- `GET /api/posts`（公开）：query `page=1,limit=20`；status=1；order `is_top DESC, created_at DESC`；含 user → `{posts:[...], total, page}`
- `GET /api/posts/{id}`（公开）：不存在或 status≠1→404 `"帖子不存在"`；成功后 `view_count` 自增 → `{post}`
- `PUT /api/posts/{id}`（登录）：404 `"帖子不存在"`；非作者→403 `"无权编辑此帖子"`；body title/content/content_type/summary/cover_url/tags → `{post}`
- `DELETE /api/posts/{id}`（登录）：404 `"帖子不存在"`；非作者→403 `"无权删除此帖子"`；软删 status=2+deleted_at → `{message:"删除成功"}`
- `GET /api/users/{id}/posts`（公开）：page/limit，user_id+status=1，order created_at DESC → `{posts:[...含 user], total, page}`（无 limit 回显）

**作者查询**：Post 列表/详情需作者信息（id/username/nickname/avatar_url）。实现用一个辅助查询：`UserMapper.findById` 逐条取作者，或 `PostMapper` 用 JOIN 一次取回。**推荐**：PostMapper 返回 Post，作者信息由 PostService 用 `userMapper.findById(post.getUserId())` 组装（数据量小，简单可靠）。注意该作者查询需包含 status 可能为 0 的用户——作者是否禁用不影响展示历史帖子，直接用 `findById`（它不筛 status）。

- [ ] **Step 1: 写集成测试（先失败）**

创建 `src/test/java/caigou/caigoupetservice/controller/PostApiIntegrationTest.java`，覆盖：创建 201、列表分页、详情 view_count 自增、越权编辑 403、删除 403/成功、用户帖子列表：

```java
package caigou.caigoupetservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * posts 模块集成测试:创建/列表/详情(浏览量自增)/编辑/软删除/用户帖子
 */
@SpringBootTest
@AutoConfigureMockMvc
class PostApiIntegrationTest {

    private static final String PREFIX = "testpost_";
    private static final ObjectMapper OM = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private Long testUserId;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM likes WHERE user_id = ?", testUserId);
        jdbc.update("DELETE FROM favorites WHERE user_id = ?", testUserId);
        jdbc.update("DELETE FROM comments WHERE user_id = ?", testUserId);
        jdbc.update("DELETE FROM posts WHERE user_id = ?", testUserId);
        jdbc.update("DELETE FROM users WHERE id = ?", testUserId);
        // 清理其它测试用户(关注/被关注)
        jdbc.update("DELETE FROM users WHERE username LIKE '" + PREFIX + "%'");
    }

    private String register(String username) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/auth/register").contentType("application/json")
                        .content(OM.writeValueAsString(Map.of("username", username, "password", "pass123"))))
                .andExpect(status().isCreated()).andReturn();
        Long uid = jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
        testUserId = uid;
        return OM.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    private String createPost(String token, String content) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/posts").header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(OM.writeValueAsString(Map.of("title", "T", "content", content))))
                .andExpect(status().isCreated()).andReturn();
        return OM.readTree(r.getResponse().getContentAsString()).get("post").get("id").asText();
    }

    @Test
    void create_shouldReturn201WithPost() throws Exception {
        String token = register(PREFIX + "c1");
        mockMvc.perform(post("/api/posts").header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(OM.writeValueAsString(Map.of("title", "标题", "content", "正文内容"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.post.content").value("正文内容"))
                .andExpect(jsonPath("$.post.user.username").value(PREFIX + "c1"));
    }

    @Test
    void create_emptyContent_shouldReturn400() throws Exception {
        String token = register(PREFIX + "c2");
        mockMvc.perform(post("/api/posts").header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(OM.writeValueAsString(Map.of("title", "T", "content", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("内容不能为空"));
    }

    @Test
    void list_shouldReturnFeed() throws Exception {
        String token = register(PREFIX + "c3");
        createPost(token, "第一条");
        mockMvc.perform(get("/api/posts").param("page", "1").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posts").isArray())
                .andExpect(jsonPath("$.total").isNumber())
                .andExpect(jsonPath("$.page").value(1));
    }

    @Test
    void getById_shouldIncrementViewCount() throws Exception {
        String token = register(PREFIX + "c4");
        String pid = createPost(token, "浏览量");
        mockMvc.perform(get("/api/posts/" + pid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.post.view_count").value(0));
        mockMvc.perform(get("/api/posts/" + pid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.post.view_count").value(1));
    }

    @Test
    void update_notOwner_shouldReturn403() throws Exception {
        String owner = register(PREFIX + "own");
        String pid = createPost(owner, "原文");
        String other = register(PREFIX + "oth");
        mockMvc.perform(put("/api/posts/" + pid).header("Authorization", "Bearer " + other)
                        .contentType("application/json")
                        .content(OM.writeValueAsString(Map.of("content", "篡改"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("无权编辑此帖子"));
    }

    @Test
    void delete_shouldSoftDelete() throws Exception {
        String token = register(PREFIX + "c5");
        String pid = createPost(token, "待删");
        mockMvc.perform(delete("/api/posts/" + pid).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("删除成功"));
        mockMvc.perform(get("/api/posts/" + pid))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("帖子不存在"));
    }

    @Test
    void userPosts_shouldReturnOwnPosts() throws Exception {
        String token = register(PREFIX + "c6");
        createPost(token, "我的帖子");
        Long uid = testUserId;
        mockMvc.perform(get("/api/users/" + uid + "/posts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posts[0].user.username").value(PREFIX + "c6"));
    }
}
```

- [ ] **Step 2: 运行确认失败**

运行：`DB_PASS='chen9911.' ./mvnw test -Dtest=PostApiIntegrationTest`
Expected: FAIL（编译失败）

- [ ] **Step 3: 创建 Post 实体与 PostMapper**

创建 `src/main/java/caigou/caigoupetservice/entity/Post.java`（Lombok `@Data`，字段对应 posts 表：id/userId/title/content/contentType/summary/coverUrl/tags(String)/status/viewCount/likeCount/commentCount/isTop/deletedAt/createdAt/updatedAt）。`tags` 实体字段为 `String`（DB JSON 原样存取），`PostView` 再转数组。

创建 `src/main/java/caigou/caigoupetservice/mapper/PostMapper.java`：

```java
package caigou.caigoupetservice.mapper;

import caigou.caigoupetservice.entity.Post;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 帖子数据访问接口:帖子 CRUD 与列表分页
 */
@Mapper
public interface PostMapper {

    /** 插入帖子,回填自增主键 */
    @Insert("INSERT INTO posts (user_id, title, content, content_type, summary, cover_url, tags, status) " +
            "VALUES (#{userId}, #{title}, #{content}, #{contentType}, #{summary}, #{coverUrl}, #{tags}, #{status})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Post post);

    /** 按主键查询帖子(不筛状态,编辑/删除越权判断用) */
    @Select("SELECT * FROM posts WHERE id = #{id}")
    Post selectById(@Param("id") Long id);

    /** 查询可见帖子(状态=1) */
    @Select("SELECT * FROM posts WHERE id = #{id} AND status = 1")
    Post selectVisibleById(@Param("id") Long id);

    /** 首页流:公开帖子,置顶优先再按时间倒序 */
    @Select("SELECT * FROM posts WHERE status = 1 ORDER BY is_top DESC, created_at DESC LIMIT #{offset}, #{limit}")
    List<Post> listFeed(@Param("offset") int offset, @Param("limit") int limit);

    /** 统计公开帖子总数 */
    @Select("SELECT COUNT(*) FROM posts WHERE status = 1")
    long countFeed();

    /** 更新帖子内容(仅非空字段) */
    @Update("<script>UPDATE posts SET updated_at = CURRENT_TIMESTAMP" +
            "<if test='title != null'> , title = #{title}</if>" +
            "<if test='content != null'> , content = #{content}</if>" +
            "<if test='contentType != null'> , content_type = #{contentType}</if>" +
            "<if test='summary != null'> , summary = #{summary}</if>" +
            "<if test='coverUrl != null'> , cover_url = #{coverUrl}</if>" +
            "<if test='tags != null'> , tags = #{tags}</if>" +
            " WHERE id = #{id}</script>")
    int update(Post post);

    /** 软删除:状态置 2 + 删除时间 */
    @Update("UPDATE posts SET status = 2, deleted_at = CURRENT_TIMESTAMP WHERE id = #{id}")
    int softDelete(@Param("id") Long id);

    /** 浏览量自增 */
    @Update("UPDATE posts SET view_count = view_count + 1 WHERE id = #{id}")
    int incrementView(@Param("id") Long id);

    /** 点赞数自增/自减 */
    @Update("UPDATE posts SET like_count = like_count + #{delta} WHERE id = #{id}")
    int changeLikeCount(@Param("id") Long id, @Param("delta") int delta);

    /** 评论数自增/自减 */
    @Update("UPDATE posts SET comment_count = comment_count + #{delta} WHERE id = #{id}")
    int changeCommentCount(@Param("id") Long id, @Param("delta") int delta);

    /** 用户公开帖子列表 */
    @Select("SELECT * FROM posts WHERE user_id = #{userId} AND status = 1 ORDER BY created_at DESC LIMIT #{offset}, #{limit}")
    List<Post> listByUser(@Param("userId") Long userId, @Param("offset") int offset, @Param("limit") int limit);

    /** 统计用户公开帖子总数 */
    @Select("SELECT COUNT(*) FROM posts WHERE user_id = #{userId} AND status = 1")
    long countByUser(@Param("userId") Long userId);
}
```

- [ ] **Step 4: 创建 PostCreateRequest 与 PostView**

创建 `src/main/java/caigou/caigoupetservice/dto/PostCreateRequest.java`：

```java
package caigou.caigoupetservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 创建/编辑帖子请求体(编辑复用)
 * 请求体字段 snake_case,用 @JsonProperty 映射到 camelCase 字段
 */
@Data
public class PostCreateRequest {

    /** 标题 */
    private String title;
    /** 正文(必填) */
    private String content;
    /** 内容类型 */
    @JsonProperty("content_type")
    private Integer contentType;
    /** 摘要 */
    private String summary;
    /** 封面图URL */
    @JsonProperty("cover_url")
    private String coverUrl;
    /** 标签数组 */
    private List<String> tags;
}
```

创建 `src/main/java/caigou/caigoupetservice/dto/PostView.java`（Lombok `@Data` + `@AllArgsConstructor` + 静态 `from(Post, UserView)`）：

```java
package caigou.caigoupetservice.dto;

import caigou.caigoupetservice.entity.Post;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 帖子视图:返回给前端,含作者 user 内嵌,字段 snake_case
 */
@Data
@AllArgsConstructor
public class PostView {

    /** 主键ID */
    private Long id;
    /** 作者ID */
    private Long user_id;
    /** 标题 */
    private String title;
    /** 正文 */
    private String content;
    /** 内容类型 */
    private Integer content_type;
    /** 摘要 */
    private String summary;
    /** 封面图URL */
    private String cover_url;
    /** 标签数组 */
    private List<String> tags;
    /** 状态 */
    private Integer status;
    /** 浏览数 */
    private Integer view_count;
    /** 点赞数 */
    private Integer like_count;
    /** 评论数 */
    private Integer comment_count;
    /** 是否置顶 */
    private Integer is_top;
    /** 创建时间 */
    private String created_at;
    /** 更新时间 */
    private String updated_at;
    /** 作者信息 */
    private UserView user;

    /** 从实体构造视图(tags 由 JSON 字符串解析为数组) */
    public static PostView from(Post p, UserView author) {
        return new PostView(p.getId(), p.getUserId(), p.getTitle(), p.getContent(), p.getContentType(),
                p.getSummary(), p.getCoverUrl(), parseTags(p.getTags()), p.getStatus(),
                p.getViewCount(), p.getLikeCount(), p.getCommentCount(), p.getIsTop(),
                p.getCreatedAt(), p.getUpdatedAt(), author);
    }

    /** 解析 tags JSON 数组字符串;空/非法返回空列表 */
    private static List<String> parseTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) {
            return List.of();
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            return om.readValue(tagsJson, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
```

> **注意**：`PostView` 字段用 snake_case 名（`user_id` 等），响应直出，不依赖全局命名策略（与现有 `UserView` 一致）。嵌套的 `user` 字段类型为 `UserView`（record，字段名已是 snake_case），直接复用。

- [ ] **Step 5: 创建 PostService**

创建 `src/main/java/caigou/caigoupetservice/service/PostService.java`：

```java
package caigou.caigoupetservice.service;

import caigou.caigoupetservice.dto.PageView;
import caigou.caigoupetservice.dto.PostCreateRequest;
import caigou.caigoupetservice.dto.PostView;
import caigou.caigoupetservice.dto.UserView;
import caigou.caigoupetservice.entity.Post;
import caigou.caigoupetservice.entity.User;
import caigou.caigoupetservice.exception.ApiException;
import caigou.caigoupetservice.mapper.PostMapper;
import caigou.caigoupetservice.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 帖子业务:创建/首页流/详情(浏览量自增)/编辑/软删除/用户帖子
 */
@Service
@RequiredArgsConstructor
public class PostService {

    private final PostMapper postMapper;
    private final UserMapper userMapper;

    /** 创建帖子:正文必填,状态固定公开 */
    public PostView create(Long userId, PostCreateRequest req) {
        if (req.getContent() == null || req.getContent().isBlank()) {
            throw new ApiException(400, "内容不能为空");
        }
        Post post = new Post();
        post.setUserId(userId);
        post.setTitle(req.getTitle());
        post.setContent(req.getContent());
        post.setContentType(req.getContentType() == null ? 0 : req.getContentType());
        post.setSummary(req.getSummary());
        post.setCoverUrl(req.getCoverUrl());
        post.setTags(toTagsJson(req.getTags()));
        post.setStatus(1);
        postMapper.insert(post);
        return PostView.from(post, authorView(userId));
    }

    /** 首页帖子流:置顶优先、时间倒序 */
    public PageView<PostView> feed(int page, int limit) {
        List<PostView> rows = postMapper.listFeed((page - 1) * limit, limit)
                .stream().map(p -> PostView.from(p, authorView(p.getUserId()))).toList();
        return new PageView<>(rows, postMapper.countFeed(), page);
    }

    /** 帖子详情:不存在返回 404,成功后浏览量自增 */
    public PostView detail(Long id) {
        Post post = postMapper.selectVisibleById(id);
        if (post == null) {
            throw new ApiException(404, "帖子不存在");
        }
        postMapper.incrementView(id);
        return PostView.from(post, authorView(post.getUserId()));
    }

    /** 编辑帖子:仅作者可编辑 */
    public PostView update(Long id, Long userId, PostCreateRequest req) {
        Post post = postMapper.selectById(id);
        if (post == null || post.getStatus() == 2) {
            throw new ApiException(404, "帖子不存在");
        }
        if (!post.getUserId().equals(userId)) {
            throw new ApiException(403, "无权编辑此帖子");
        }
        Post upd = new Post();
        upd.setId(id);
        upd.setTitle(req.getTitle());
        upd.setContent(req.getContent());
        upd.setContentType(req.getContentType());
        upd.setSummary(req.getSummary());
        upd.setCoverUrl(req.getCoverUrl());
        upd.setTags(req.getTags() == null ? null : toTagsJson(req.getTags()));
        postMapper.update(upd);
        return detail(id);
    }

    /** 软删除帖子:仅作者可删 */
    public void delete(Long id, Long userId) {
        Post post = postMapper.selectById(id);
        if (post == null || post.getStatus() == 2) {
            throw new ApiException(404, "帖子不存在");
        }
        if (!post.getUserId().equals(userId)) {
            throw new ApiException(403, "无权删除此帖子");
        }
        postMapper.softDelete(id);
    }

    /** 用户公开帖子列表 */
    public PageView<PostView> userPosts(Long userId, int page, int limit) {
        List<PostView> rows = postMapper.listByUser(userId, (page - 1) * limit, limit)
                .stream().map(p -> PostView.from(p, authorView(p.getUserId()))).toList();
        return new PageView<>(rows, postMapper.countByUser(userId), page);
    }

    /** 查询作者视图 */
    private UserView authorView(Long userId) {
        User u = userMapper.findById(userId);
        return u == null ? null : UserView.from(u);
    }

    /** 标签数组序列化为 JSON 字符串 */
    private String toTagsJson(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return "[]";
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(tags);
        } catch (Exception e) {
            return "[]";
        }
    }
}
```

- [ ] **Step 6: 创建 PostController**

创建 `src/main/java/caigou/caigoupetservice/controller/PostController.java`：

```java
package caigou.caigoupetservice.controller;

import caigou.caigoupetservice.annotation.PublicEndpoint;
import caigou.caigoupetservice.dto.PageView;
import caigou.caigoupetservice.dto.PostCreateRequest;
import caigou.caigoupetservice.dto.PostView;
import caigou.caigoupetservice.service.PostService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 帖子控制器:创建/列表/详情/编辑/删除
 */
@RestController
@RequestMapping("/api/posts")
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;

    /** 创建帖子:成功 201 */
    @PostMapping
    public ResponseEntity<Map<String, PostView>> create(@RequestBody PostCreateRequest req,
                                                        HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("currentUserId");
        return ResponseEntity.status(201).body(Map.of("post", postService.create(userId, req)));
    }

    /** 首页流(公开) */
    @PublicEndpoint
    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int limit) {
        PageView<PostView> view = postService.feed(page, limit);
        return Map.of("posts", view.getRows(), "total", view.getTotal(), "page", view.getPage());
    }

    /** 详情(公开) */
    @PublicEndpoint
    @GetMapping("/{id}")
    public Map<String, PostView> detail(@PathVariable Long id) {
        return Map.of("post", postService.detail(id));
    }

    /** 编辑(作者) */
    @PutMapping("/{id}")
    public Map<String, PostView> update(@PathVariable Long id, @RequestBody PostCreateRequest req,
                                        HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("currentUserId");
        return Map.of("post", postService.update(id, userId, req));
    }

    /** 删除(作者) */
    @DeleteMapping("/{id}")
    public Map<String, String> delete(@PathVariable Long id, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("currentUserId");
        postService.delete(id, userId);
        return Map.of("message", "删除成功");
    }
}
```

- [ ] **Step 7: UserController 追加用户帖子端点**

在 `UserController` 加一个依赖 `PostService` 的构造字段，并追加端点：

```java
    private final UserService userService;
    private final PostService postService;   // 追加

    /** 用户公开帖子列表(公开) */
    @PublicEndpoint
    @GetMapping("/{id}/posts")
    public Map<String, Object> userPosts(@PathVariable Long id,
                                         @RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int limit) {
        caigou.caigoupetservice.dto.PageView<PostView> view = postService.userPosts(id, page, limit);
        return Map.of("posts", view.getRows(), "total", view.getTotal(), "page", view.getPage());
    }
```

（`UserController` 需新增 `private final PostService postService;` 构造字段；用户帖子逻辑直接复用 `PostService.userPosts`，`UserService` 不重复实现。）

- [ ] **Step 8: 运行测试确认通过**

运行：`DB_PASS='chen9911.' ./mvnw test -Dtest=PostApiIntegrationTest`
Expected: PASS

- [ ] **Step 9: 回归前序测试**

运行：`DB_PASS='chen9911.' ./mvnw test -Dtest=AuthApiIntegrationTest,ResourceApiIntegrationTest,UserApiIntegrationTest`
Expected: 全部通过

- [ ] **Step 10: 提交**

```bash
git add src/main/java/caigou/caigoupetservice/entity/Post.java src/main/java/caigou/caigoupetservice/mapper/PostMapper.java src/main/java/caigou/caigoupetservice/dto/PostCreateRequest.java src/main/java/caigou/caigoupetservice/dto/PostView.java src/main/java/caigou/caigoupetservice/service/PostService.java src/main/java/caigou/caigoupetservice/controller/PostController.java src/main/java/caigou/caigoupetservice/controller/UserController.java src/test/java/caigou/caigoupetservice/controller/PostApiIntegrationTest.java
git commit -m "feat: posts 模块——创建/列表/详情(浏览量自增)/编辑/软删除/用户帖子"
```

---

## Task 6: likes / favorites / follows 模块

**Files:**
- Create: `src/main/java/caigou/caigoupetservice/entity/Like.java`、`entity/Favorite.java`、`entity/Follow.java`
- Create: `src/main/java/caigou/caigoupetservice/mapper/LikeMapper.java`、`mapper/FavoriteMapper.java`、`mapper/FollowMapper.java`
- Create: `src/main/java/caigou/caigoupetservice/service/LikeService.java`、`service/FavoriteService.java`、`service/FollowService.java`
- Create: `src/main/java/caigou/caigoupetservice/controller/LikeController.java`、`controller/FavoriteController.java`、`controller/FollowController.java`
- Modify: `src/main/java/caigou/caigoupetservice/mapper/UserMapper.java`（追加计数自增/自减方法）
- Modify: `src/main/java/caigou/caigoupetservice/service/PostService.java`（新增 `postViewById` 公开方法）
- Test: `src/test/java/caigou/caigoupetservice/controller/CommunityRelationApiIntegrationTest.java`

**Interfaces:**
- Consumes: `PageView<T>`、`ApiException`、`currentUserId`、`PostMapper.selectVisibleById/changeLikeCount`（Task 5）、`UserMapper`（计数）、`PostService` 或 `PostMapper.listByUser` 附近的方法（点赞/收藏列表返回帖子）
- Produces:
  - `LikeController`：`POST /api/likes/{postId}`、`DELETE /api/likes/{postId}`、`GET /api/likes/user/{userId}`
  - `FavoriteController`：`POST /api/favorites/{postId}`、`DELETE /api/favorites/{postId}`、`GET /api/favorites/user/{userId}`
  - `FollowController`：`POST /api/follow/{userId}`、`DELETE /api/follow/{userId}`、`GET /api/follow/{userId}/followers`、`GET /api/follow/{userId}/following`
  - `LikeService`/`FavoriteService` 的 `toggle`（幂等）与 `listUserPosts`；`FollowService` 的 `follow/unfollow/listFollowers/listFollowing`

**契约对齐（Express，likes 前缀 /api/likes、favorites /api/favorites、follow 前缀 /api/follow 单数）：**
- `POST /api/likes/{postId}`（登录）：帖不存在→404 `"帖子不存在"`；幂等——新建 201 `{like, created:true}`，已存在 200 `{like, created:false}`（**无 409**）；仅新建时 post.like_count++、作者.likes_count++
- `DELETE /api/likes/{postId}`（登录）：无记录→404 `"未点赞"`；成功 `{message:"取消点赞成功"}`；post.like_count--、作者.likes_count--
- `GET /api/likes/user/{userId}`（公开）：page/limit → `{posts:[Post 含 user], total, page}`（key 是 posts；只含 status=1 帖子）
- `POST /api/favorites/{postId}`（登录）：404 `"帖子不存在"`；201/200 `{favorite, created}` 幂等；仅新建时**本人** favorites_count++
- `DELETE /api/favorites/{postId}`：404 `"未收藏"`；`{message:"取消收藏成功"}`
- `GET /api/favorites/user/{userId}`（公开）：`{posts,total,page}` 同上
- `POST /api/follow/{userId}`（登录）：关注自己→400 `"不能关注自己"`；目标不存在或 status≠1→404 `"用户不存在"`；幂等 201/200 `{follow, created}`；新建时 follower.following_count++、目标.followers_count++
- `DELETE /api/follow/{userId}`：无记录→404 `"未关注此用户"`；`{message:"取消关注成功"}`
- `GET /api/follow/{userId}/followers`：`{followers:[{..., follower:{id,username,nickname,avatar_url}}], total, page}`
- `GET /api/follow/{userId}/following`：`{following:[{..., following:{id,username,nickname,avatar_url}}], total, page}`

**点赞/收藏列表返回的帖子**：复用 `PostMapper` 查询——需要"按 like/favorite 记录反查帖子"。实现：LikeMapper 查出当前用户点赞的 post_id 列表（分页 + 总数），再按 post_id 取可见帖子。为简化，用 JOIN 查询：`SELECT p.* FROM posts p JOIN likes l ON p.id = l.post_id WHERE l.user_id = ? AND p.status = 1 ORDER BY l.created_at DESC LIMIT ?, ?`，count 同理。PostService 暴露 `postViewById`（按 id 查 PostView）供组装作者。

- [ ] **Step 1: 写集成测试（先失败）**

创建 `src/test/java/caigou/caigoupetservice/controller/CommunityRelationApiIntegrationTest.java`，覆盖：点赞新建/重复幂等（第二次 200 created=false）、取消点赞、点赞列表返回帖子、收藏幂等、关注自己 400、关注+取关、粉丝/关注列表：

```java
package caigou.caigoupetservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 点赞/收藏/关注 模块集成测试:幂等/越权/计数/列表
 */
@SpringBootTest
@AutoConfigureMockMvc
class CommunityRelationApiIntegrationTest {

    private static final String PREFIX = "testrel_";
    private static final ObjectMapper OM = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        // 清理本类创建的测试用户及其关联数据
        jdbc.update("DELETE FROM likes WHERE user_id IN (SELECT id FROM users WHERE username LIKE '" + PREFIX + "%')");
        jdbc.update("DELETE FROM favorites WHERE user_id IN (SELECT id FROM users WHERE username LIKE '" + PREFIX + "%')");
        jdbc.update("DELETE FROM follows WHERE user_id IN (SELECT id FROM users WHERE username LIKE '" + PREFIX + "%') OR follower_id IN (SELECT id FROM users WHERE username LIKE '" + PREFIX + "%')");
        jdbc.update("DELETE FROM posts WHERE user_id IN (SELECT id FROM users WHERE username LIKE '" + PREFIX + "%')");
        jdbc.update("DELETE FROM users WHERE username LIKE '" + PREFIX + "%'");
    }

    private String register(String username) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/auth/register").contentType("application/json")
                        .content(OM.writeValueAsString(Map.of("username", username, "password", "pass123"))))
                .andExpect(status().isCreated()).andReturn();
        return OM.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    private long uid(String username) {
        return jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
    }

    private String createPost(String token, String content) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/posts").header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(OM.writeValueAsString(Map.of("content", content))))
                .andExpect(status().isCreated()).andReturn();
        return OM.readTree(r.getResponse().getContentAsString()).get("post").get("id").asText();
    }

    @Test
    void like_createAndRepeat_shouldBeIdempotent() throws Exception {
        String token = register(PREFIX + "a1");
        String pid = createPost(token, "被赞帖");
        mockMvc.perform(post("/api/likes/" + pid).header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.created").value(true));
        // 重复点赞:200 + created=false,计数不重复增加
        mockMvc.perform(post("/api/likes/" + pid).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(false));
        Integer likeCount = jdbc.queryForObject("SELECT like_count FROM posts WHERE id = ?", Integer.class, Long.parseLong(pid));
        org.junit.jupiter.api.Assertions.assertEquals(1, likeCount, "重复点赞计数不应增加");
    }

    @Test
    void like_missingPost_shouldReturn404() throws Exception {
        String token = register(PREFIX + "a2");
        mockMvc.perform(post("/api/likes/999999").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("帖子不存在"));
    }

    @Test
    void unlike_shouldReturnMessage() throws Exception {
        String token = register(PREFIX + "a3");
        String pid = createPost(token, "取消赞");
        mockMvc.perform(post("/api/likes/" + pid).header("Authorization", "Bearer " + token)).andExpect(status().isCreated());
        mockMvc.perform(delete("/api/likes/" + pid).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("取消点赞成功"));
        // 未点赞时取消失败
        mockMvc.perform(delete("/api/likes/" + pid).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("未点赞"));
    }

    @Test
    void likeList_shouldReturnPosts() throws Exception {
        String token = register(PREFIX + "a4");
        String pid = createPost(token, "列表里的帖子");
        mockMvc.perform(post("/api/likes/" + pid).header("Authorization", "Bearer " + token)).andExpect(status().isCreated());
        mockMvc.perform(get("/api/likes/user/" + uid(PREFIX + "a4")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posts[0].content").value("列表里的帖子"));
    }

    @Test
    void follow_self_shouldReturn400() throws Exception {
        String token = register(PREFIX + "b1");
        mockMvc.perform(post("/api/follow/" + uid(PREFIX + "b1")).header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("不能关注自己"));
    }

    @Test
    void follow_thenUnfollow_shouldWork() throws Exception {
        String a = register(PREFIX + "c1");
        String b = register(PREFIX + "c2");
        mockMvc.perform(post("/api/follow/" + uid(PREFIX + "c2")).header("Authorization", "Bearer " + a))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.created").value(true));
        mockMvc.perform(delete("/api/follow/" + uid(PREFIX + "c2")).header("Authorization", "Bearer " + a))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("取消关注成功"));
    }

    @Test
    void followersList_shouldReturnFollower() throws Exception {
        String a = register(PREFIX + "d1");
        register(PREFIX + "d2");
        mockMvc.perform(post("/api/follow/" + uid(PREFIX + "d1")).header("Authorization", "Bearer " + register(PREFIX + "d3")))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/follow/" + uid(PREFIX + "d1") + "/followers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.followers[0].follower.username").isNotEmpty());
    }
}
```

> **注意**：`followersList` 中第三个注册会覆盖 b 的 token 变量但无关紧要；测试逻辑以能跑通为准，若个别断言不稳定，可拆分成独立小测试。

- [ ] **Step 2: 运行确认失败**

运行：`DB_PASS='chen9911.' ./mvnw test -Dtest=CommunityRelationApiIntegrationTest`
Expected: FAIL（编译失败）

- [ ] **Step 3: 创建实体（Like/Favorite/Follow，Lombok @Data）**

三个实体字段：`Like`（id/userId/postId/createdAt）、`Favorite`（同 Like）、`Follow`（id/userId/followerId/createdAt）。均为 `@Data` 类，字段驼峰对应表。

- [ ] **Step 4: 创建 Mapper**

创建 `src/main/java/caigou/caigoupetservice/mapper/LikeMapper.java`：

```java
package caigou.caigoupetservice.mapper;

import caigou.caigoupetservice.entity.Like;
import caigou.caigoupetservice.entity.Post;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 点赞数据访问接口:幂等插入/删除与按用户反查帖子
 */
@Mapper
public interface LikeMapper {

    /** 插入点赞记录,回填自增主键(唯一约束兜底重复) */
    @Insert("INSERT INTO likes (user_id, post_id) VALUES (#{userId}, #{postId})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Like like);

    /** 查询指定用户对指定帖子的点赞记录 */
    @Select("SELECT * FROM likes WHERE user_id = #{userId} AND post_id = #{postId}")
    Like find(@Param("userId") Long userId, @Param("postId") Long postId);

    /** 删除点赞记录 */
    @Delete("DELETE FROM likes WHERE user_id = #{userId} AND post_id = #{postId}")
    int delete(@Param("userId") Long userId, @Param("postId") Long postId);

    /** 分页查询用户点赞过的可见帖子(JOIN posts,时间倒序) */
    @Select("SELECT p.* FROM posts p JOIN likes l ON p.id = l.post_id " +
            "WHERE l.user_id = #{userId} AND p.status = 1 ORDER BY l.created_at DESC LIMIT #{offset}, #{limit}")
    List<Post> listUserPosts(@Param("userId") Long userId, @Param("offset") int offset, @Param("limit") int limit);

    /** 统计用户点赞过的可见帖子总数 */
    @Select("SELECT COUNT(*) FROM posts p JOIN likes l ON p.id = l.post_id WHERE l.user_id = #{userId} AND p.status = 1")
    long countUserPosts(@Param("userId") Long userId);
}
```

> `FavoriteMapper` 与 `LikeMapper` **完全同构**（favorites 表与 likes 表字段一致）：复制 `LikeMapper`，把表名 `likes`→`favorites`、实体 `Like`→`Favorite` 即可。

创建 `src/main/java/caigou/caigoupetservice/mapper/FollowMapper.java`：

```java
package caigou.caigoupetservice.mapper;

import caigou.caigoupetservice.entity.Follow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 关注数据访问接口:幂等关注/取关与粉丝/关注列表
 */
@Mapper
public interface FollowMapper {

    /** 插入关注记录(user_id=被关注者, follower_id=关注者) */
    @Insert("INSERT INTO follows (user_id, follower_id) VALUES (#{userId}, #{followerId})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Follow follow);

    /** 查询是否已关注 */
    @Select("SELECT * FROM follows WHERE user_id = #{userId} AND follower_id = #{followerId}")
    Follow find(@Param("userId") Long userId, @Param("followerId") Long followerId);

    /** 删除关注记录 */
    @Delete("DELETE FROM follows WHERE user_id = #{userId} AND follower_id = #{followerId}")
    int delete(@Param("userId") Long userId, @Param("followerId") Long followerId);

    /** 粉丝列表:被关注者=userId 的记录 */
    @Select("SELECT * FROM follows WHERE user_id = #{userId} ORDER BY created_at DESC LIMIT #{offset}, #{limit}")
    List<Follow> listFollowers(@Param("userId") Long userId, @Param("offset") int offset, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM follows WHERE user_id = #{userId}")
    long countFollowers(@Param("userId") Long userId);

    /** 关注列表:关注者=userId 的记录 */
    @Select("SELECT * FROM follows WHERE follower_id = #{userId} ORDER BY created_at DESC LIMIT #{offset}, #{limit}")
    List<Follow> listFollowing(@Param("userId") Long userId, @Param("offset") int offset, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM follows WHERE follower_id = #{userId}")
    long countFollowing(@Param("userId") Long userId);
}
```

> 点赞/收藏列表的「按 post_id 取可见帖子 + 作者」复用 `PostService.postViewById(id)`——本 Task 在 `PostService` 新增公开方法：

```java
    /** 按 id 查询可见帖子的视图(供点赞/收藏列表用,不存在返回 null) */
    public PostView postViewById(Long id) {
        Post post = postMapper.selectVisibleById(id);
        return post == null ? null : PostView.from(post, authorView(post.getUserId()));
    }
```

- [ ] **Step 5: 创建 Service**

创建 `src/main/java/caigou/caigoupetservice/service/LikeService.java`：

```java
package caigou.caigoupetservice.service;

import caigou.caigoupetservice.dto.PageView;
import caigou.caigoupetservice.dto.PostView;
import caigou.caigoupetservice.entity.Like;
import caigou.caigoupetservice.exception.ApiException;
import caigou.caigoupetservice.mapper.LikeMapper;
import caigou.caigoupetservice.mapper.PostMapper;
import caigou.caigoupetservice.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 点赞业务:幂等点赞/取消点赞/点赞帖子列表,维护帖子与作者计数
 */
@Service
@RequiredArgsConstructor
public class LikeService {

    private final LikeMapper likeMapper;
    private final PostMapper postMapper;
    private final UserMapper userMapper;
    private final PostService postService;

    /** 点赞:帖不存在 404;已点赞幂等返回 created=false;新建时帖子点赞数与作者获赞数 +1 */
    public Map<String, Object> like(Long userId, Long postId) {
        if (postMapper.selectVisibleById(postId) == null) {
            throw new ApiException(404, "帖子不存在");
        }
        Like existing = likeMapper.find(userId, postId);
        if (existing != null) {
            return Map.of("like", existing, "created", false);
        }
        Like like = new Like();
        like.setUserId(userId);
        like.setPostId(postId);
        likeMapper.insert(like);
        Long authorId = postMapper.selectById(postId).getUserId();
        postMapper.changeLikeCount(postId, 1);
        userMapper.changeLikesCount(authorId, 1);
        return Map.of("like", like, "created", true);
    }

    /** 取消点赞:未点赞 404;成功后帖子点赞数与作者获赞数 -1 */
    public Map<String, String> unlike(Long userId, Long postId) {
        if (likeMapper.find(userId, postId) == null) {
            throw new ApiException(404, "未点赞");
        }
        likeMapper.delete(userId, postId);
        Long authorId = postMapper.selectById(postId).getUserId();
        postMapper.changeLikeCount(postId, -1);
        userMapper.changeLikesCount(authorId, -1);
        return Map.of("message", "取消点赞成功");
    }

    /** 用户点赞过的帖子列表(分页) */
    public PageView<PostView> listUserPosts(Long userId, int page, int limit) {
        List<PostView> rows = likeMapper.listUserPosts(userId, (page - 1) * limit, limit)
                .stream().map(p -> postService.postViewById(p.getId()))
                .filter(java.util.Objects::nonNull).toList();
        return new PageView<>(rows, likeMapper.countUserPosts(userId), page);
    }
}
```

> `FavoriteService` 与 `LikeService` **同构**：复制为 `favorite/unfavorite/listUserPosts`，改用 `FavoriteMapper`（JOIN favorites 表），计数用 `userMapper.changeFavoritesCount(userId, ±1)`（本人收藏数，非作者）。错误文案：`"帖子不存在"` / `"未收藏"` / `"取消收藏成功"`。

创建 `src/main/java/caigou/caigoupetservice/service/FollowService.java`：

```java
package caigou.caigoupetservice.service;

import caigou.caigoupetservice.dto.UserView;
import caigou.caigoupetservice.entity.Follow;
import caigou.caigoupetservice.entity.User;
import caigou.caigoupetservice.exception.ApiException;
import caigou.caigoupetservice.mapper.FollowMapper;
import caigou.caigoupetservice.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 关注业务:幂等关注/取关/粉丝与关注列表,维护双方计数
 */
@Service
@RequiredArgsConstructor
public class FollowService {

    private final FollowMapper followMapper;
    private final UserMapper userMapper;

    /** 关注:关注自己 400;目标不存在或禁用 404;已关注幂等返回 created=false */
    public Map<String, Object> follow(Long userId, Long targetId) {
        if (targetId.equals(userId)) {
            throw new ApiException(400, "不能关注自己");
        }
        User target = userMapper.findById(targetId);
        if (target == null || target.getStatus() == null || target.getStatus() != 1) {
            throw new ApiException(404, "用户不存在");
        }
        Follow existing = followMapper.find(targetId, userId);
        if (existing != null) {
            return Map.of("follow", existing, "created", false);
        }
        Follow follow = new Follow();
        follow.setUserId(targetId);
        follow.setFollowerId(userId);
        followMapper.insert(follow);
        userMapper.changeFollowingCount(userId, 1);
        userMapper.changeFollowersCount(targetId, 1);
        return Map.of("follow", follow, "created", true);
    }

    /** 取消关注:未关注 404;成功后双方计数 -1 */
    public Map<String, String> unfollow(Long userId, Long targetId) {
        if (followMapper.find(targetId, userId) == null) {
            throw new ApiException(404, "未关注此用户");
        }
        followMapper.delete(targetId, userId);
        userMapper.changeFollowingCount(userId, -1);
        userMapper.changeFollowersCount(targetId, -1);
        return Map.of("message", "取消关注成功");
    }

    /** 粉丝列表:每条记录内嵌 follower 用户视图 */
    public Map<String, Object> listFollowers(Long userId, int page, int limit) {
        List<Follow> rows = followMapper.listFollowers(userId, (page - 1) * limit, limit);
        List<Map<String, Object>> list = rows.stream().map(f -> {
            User follower = userMapper.findById(f.getFollowerId());
            return Map.<String, Object>of(
                    "id", f.getId(), "created_at", f.getCreatedAt(),
                    "follower", follower == null ? null : UserView.from(follower));
        }).toList();
        return Map.of("followers", list, "total", followMapper.countFollowers(userId), "page", page);
    }

    /** 关注列表:每条记录内嵌 following 用户视图 */
    public Map<String, Object> listFollowing(Long userId, int page, int limit) {
        List<Follow> rows = followMapper.listFollowing(userId, (page - 1) * limit, limit);
        List<Map<String, Object>> list = rows.stream().map(f -> {
            User target = userMapper.findById(f.getUserId());
            return Map.<String, Object>of(
                    "id", f.getId(), "created_at", f.getCreatedAt(),
                    "following", target == null ? null : UserView.from(target));
        }).toList();
        return Map.of("following", list, "total", followMapper.countFollowing(userId), "page", page);
    }
}
```

**UserMapper 追加计数方法**（均在 `users` 表，本 Task 实现时添加到 `UserMapper`）：

```java
    @Update("UPDATE users SET likes_count = likes_count + #{delta} WHERE id = #{id}")
    int changeLikesCount(@Param("id") Long id, @Param("delta") int delta);

    @Update("UPDATE users SET favorites_count = favorites_count + #{delta} WHERE id = #{id}")
    int changeFavoritesCount(@Param("id") Long id, @Param("delta") int delta);

    @Update("UPDATE users SET following_count = following_count + #{delta} WHERE id = #{id}")
    int changeFollowingCount(@Param("id") Long id, @Param("delta") int delta);

    @Update("UPDATE users SET followers_count = followers_count + #{delta} WHERE id = #{id}")
    int changeFollowersCount(@Param("id") Long id, @Param("delta") int delta);
```

> **like/favorite/follow 对象序列化说明**：Express 返回的 `like`/`favorite`/`follow` 记录字段为 snake_case（`user_id`/`post_id`），本计划用实体（camelCase）直出。前端只消费 `created` 字段，实体直出可接受；如需与 Express 完全一致的记录字段，可在批次 5 补对应 View。**以测试通过为准**。

- [ ] **Step 6: 创建 Controller（三个）**

创建 `src/main/java/caigou/caigoupetservice/controller/LikeController.java`：

```java
package caigou.caigoupetservice.controller;

import caigou.caigoupetservice.annotation.PublicEndpoint;
import caigou.caigoupetservice.dto.PageView;
import caigou.caigoupetservice.dto.PostView;
import caigou.caigoupetservice.service.LikeService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 点赞控制器:点赞(幂等)/取消点赞/点赞帖子列表
 */
@RestController
@RequestMapping("/api/likes")
@RequiredArgsConstructor
public class LikeController {

    private final LikeService likeService;

    /** 点赞:新建 201,已存在 200(created=false) */
    @PostMapping("/{postId}")
    public ResponseEntity<Map<String, Object>> like(@PathVariable Long postId, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("currentUserId");
        Map<String, Object> result = likeService.like(userId, postId);
        boolean created = (boolean) result.get("created");
        return ResponseEntity.status(created ? 201 : 200).body(result);
    }

    /** 取消点赞 */
    @DeleteMapping("/{postId}")
    public Map<String, String> unlike(@PathVariable Long postId, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("currentUserId");
        return likeService.unlike(userId, postId);
    }

    /** 用户点赞过的帖子列表(公开) */
    @PublicEndpoint
    @GetMapping("/user/{userId}")
    public Map<String, Object> list(@PathVariable Long userId,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int limit) {
        PageView<PostView> view = likeService.listUserPosts(userId, page, limit);
        return Map.of("posts", view.getRows(), "total", view.getTotal(), "page", view.getPage());
    }
}
```

> `FavoriteController`（前缀 `/api/favorites`）与 `LikeController` **同构**：改注入 `FavoriteService`，POST 返回 key 用 `favorite`，列表路径 `/favorites/user/{userId}`。**其 `GET /favorites/user/{userId}` 加 `@PublicEndpoint`**（公开列表），并加 `import caigou.caigoupetservice.annotation.PublicEndpoint;`。
> `FollowController`（前缀 `/api/follow`）注入 `FollowService`：`POST /api/follow/{userId}`（201/200 + `{follow, created}`）、`DELETE /api/follow/{userId}`（`{message:"取消关注成功"}`）、`GET /api/follow/{userId}/followers`（`{followers,total,page}`）、`GET /api/follow/{userId}/following`（`{following,total,page}`）。**`GET .../followers` 与 `GET .../following` 均加 `@PublicEndpoint`**，并加 `import caigou.caigoupetservice.annotation.PublicEndpoint;`。三个 controller 均从 `request.getAttribute("currentUserId")` 取当前用户。

- [ ] **Step 7: 运行测试确认通过**

运行：`DB_PASS='chen9911.' ./mvnw test -Dtest=CommunityRelationApiIntegrationTest`
Expected: PASS

- [ ] **Step 8: 回归全部测试**

运行：`DB_PASS='chen9911.' ./mvnw test`
Expected: 全部通过（auth/resource/user/post/relation + schema smoke）

- [ ] **Step 9: 提交**

```bash
git add src/main/java/caigou/caigoupetservice/entity/Like.java src/main/java/caigou/caigoupetservice/entity/Favorite.java src/main/java/caigou/caigoupetservice/entity/Follow.java src/main/java/caigou/caigoupetservice/mapper/LikeMapper.java src/main/java/caigou/caigoupetservice/mapper/FavoriteMapper.java src/main/java/caigou/caigoupetservice/mapper/FollowMapper.java src/main/java/caigou/caigoupetservice/mapper/UserMapper.java src/main/java/caigou/caigoupetservice/service/LikeService.java src/main/java/caigou/caigoupetservice/service/FavoriteService.java src/main/java/caigou/caigoupetservice/service/FollowService.java src/main/java/caigou/caigoupetservice/controller/LikeController.java src/main/java/caigou/caigoupetservice/controller/FavoriteController.java src/main/java/caigou/caigoupetservice/controller/FollowController.java src/test/java/caigou/caigoupetservice/controller/CommunityRelationApiIntegrationTest.java
git commit -m "feat: 点赞/收藏/关注模块——幂等操作与计数维护、粉丝/关注列表"
```

---

## Task 7: comments 模块（两级树形评论）

**Files:**
- Create: `src/main/java/caigou/caigoupetservice/entity/Comment.java`
- Create: `src/main/java/caigou/caigoupetservice/mapper/CommentMapper.java`
- Create: `src/main/java/caigou/caigoupetservice/dto/CommentView.java`
- Create: `src/main/java/caigou/caigoupetservice/service/CommentService.java`
- Create: `src/main/java/caigou/caigoupetservice/controller/CommentController.java`
- Modify: `src/main/java/caigou/caigoupetservice/mapper/PostMapper.java`（已含 `changeCommentCount`）
- Test: `src/test/java/caigou/caigoupetservice/controller/CommentApiIntegrationTest.java`

**Interfaces:**
- Consumes: `PageView<T>`、`ApiException`、`currentUserId`、`PostMapper.selectVisibleById/changeCommentCount`、`UserMapper.findById`
- Produces:
  - `CommentMapper` 方法：`insert/selectById/listRoots(postId, offset, limit)/countRoots(postId)/listByRootIds(List<Long>)/softDelete/changeLikeCount`
  - `CommentView`（Lombok：id/post_id/user_id/parent_id/root_id/content/like_count/status/created_at + user(UserView) + replies(List<CommentView>)）
  - `CommentController`：`POST /api/comments`、`GET /api/comments/post/{postId}`、`DELETE /api/comments/{id}`

**契约对齐（Express comments.js，前缀 /api/comments）：**
- `POST /api/comments`（登录）：body post_id/content(必填)/parent_id；post_id 或 content 空→400 `"post_id 和 content 不能为空"`；帖不存在或 status≠1→404 `"帖子不存在"`；父评论不存在→404 `"父评论不存在"`；root_id=父评论的 root_id 或父 id；post.comment_count++ → 201 `{comment:{..., user}}`
- `GET /api/comments/post/{postId}`（公开）：page/limit；根评论 where post_id, status=1, parent_id IS NULL，created_at DESC；子评论 where root_id IN(根), status=1，created_at ASC → `{comments:[{...根, replies:[子]}], total, page}`（total=根评论数）
- `DELETE /api/comments/{id}`（登录）：404 `"评论不存在"`；非作者→403 `"无权删除此评论"`；status=0 软删；post.comment_count-- → `{message:"删除成功"}`

- [ ] **Step 1: 写集成测试（先失败）**

创建 `src/test/java/caigou/caigoupetservice/controller/CommentApiIntegrationTest.java`，覆盖：发根评论 201、回复挂到 replies、空参数 400、帖不存在 404、删除 403/成功、删除后评论计数减少：

```java
package caigou.caigoupetservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * comments 模块集成测试:根评论/回复树/参数校验/软删除
 */
@SpringBootTest
@AutoConfigureMockMvc
class CommentApiIntegrationTest {

    private static final String PREFIX = "testcmt_";
    private static final ObjectMapper OM = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM comments WHERE user_id IN (SELECT id FROM users WHERE username LIKE '" + PREFIX + "%')");
        jdbc.update("DELETE FROM posts WHERE user_id IN (SELECT id FROM users WHERE username LIKE '" + PREFIX + "%')");
        jdbc.update("DELETE FROM users WHERE username LIKE '" + PREFIX + "%'");
    }

    private String register(String username) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/auth/register").contentType("application/json")
                        .content(OM.writeValueAsString(Map.of("username", username, "password", "pass123"))))
                .andExpect(status().isCreated()).andReturn();
        return OM.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    private String createPost(String token, String content) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/posts").header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(OM.writeValueAsString(Map.of("content", content))))
                .andExpect(status().isCreated()).andReturn();
        return OM.readTree(r.getResponse().getContentAsString()).get("post").get("id").asText();
    }

    private String postComment(String token, long postId, String content, Long parentId) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("post_id", postId);
        body.put("content", content);
        if (parentId != null) body.put("parent_id", parentId);
        MvcResult r = mockMvc.perform(post("/api/comments").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content(OM.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn();
        return OM.readTree(r.getResponse().getContentAsString()).get("comment").get("id").asText();
    }

    @Test
    void commentRoot_thenReply_shouldBuildTree() throws Exception {
        String token = register(PREFIX + "c1");
        String pid = createPost(token, "评论帖");
        long postId = Long.parseLong(pid);
        String rootId = postComment(token, postId, "一级评论", null);
        String replyId = postComment(token, postId, "回复一级", Long.parseLong(rootId));
        mockMvc.perform(get("/api/comments/post/" + postId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comments[0].content").value("一级评论"))
                .andExpect(jsonPath("$.comments[0].replies[0].content").value("回复一级"));
    }

    @Test
    void comment_missingParams_shouldReturn400() throws Exception {
        String token = register(PREFIX + "c2");
        mockMvc.perform(post("/api/comments").header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(OM.writeValueAsString(Map.of("post_id", 1, "content", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("post_id 和 content 不能为空"));
    }

    @Test
    void comment_missingPost_shouldReturn404() throws Exception {
        String token = register(PREFIX + "c3");
        mockMvc.perform(post("/api/comments").header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(OM.writeValueAsString(Map.of("post_id", 999999, "content", "x"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("帖子不存在"));
    }

    @Test
    void delete_notOwner_shouldReturn403() throws Exception {
        String a = register(PREFIX + "c4");
        String pid = createPost(a, "评论帖2");
        String rootId = postComment(a, Long.parseLong(pid), "待删评论", null);
        String b = register(PREFIX + "c5");
        mockMvc.perform(delete("/api/comments/" + rootId).header("Authorization", "Bearer " + b))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("无权删除此评论"));
    }

    @Test
    void delete_shouldReduceCommentCount() throws Exception {
        String token = register(PREFIX + "c6");
        String pid = createPost(token, "评论帖3");
        String rootId = postComment(token, Long.parseLong(pid), "待删", null);
        Integer before = jdbc.queryForObject("SELECT comment_count FROM posts WHERE id = ?", Integer.class, Long.parseLong(pid));
        mockMvc.perform(delete("/api/comments/" + rootId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("删除成功"));
        Integer after = jdbc.queryForObject("SELECT comment_count FROM posts WHERE id = ?", Integer.class, Long.parseLong(pid));
        org.junit.jupiter.api.Assertions.assertEquals(before - 1, after, "删除评论后计数应减一");
    }
}
```

- [ ] **Step 2: 运行确认失败**

运行：`DB_PASS='chen9911.' ./mvnw test -Dtest=CommentApiIntegrationTest`
Expected: FAIL（编译失败）

- [ ] **Step 3: 创建 Comment 实体与 CommentMapper**

`Comment`（Lombok `@Data`）：id/postId/userId/parentId/rootId/content/likeCount/status/createdAt/updatedAt。

`CommentMapper`：
```java
@Mapper
public interface CommentMapper {
    @Insert("INSERT INTO comments (post_id, user_id, parent_id, root_id, content) " +
            "VALUES (#{postId}, #{userId}, #{parentId}, #{rootId}, #{content})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Comment comment);

    @Select("SELECT * FROM comments WHERE id = #{id}")
    Comment selectById(@Param("id") Long id);

    // 根评论(一级):post_id 下 parent_id 为空且未删除,时间倒序
    @Select("SELECT * FROM comments WHERE post_id = #{postId} AND status = 1 AND parent_id IS NULL " +
            "ORDER BY created_at DESC LIMIT #{offset}, #{limit}")
    List<Comment> listRoots(@Param("postId") Long postId, @Param("offset") int offset, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM comments WHERE post_id = #{postId} AND status = 1 AND parent_id IS NULL")
    long countRoots(@Param("postId") Long postId);

    // 子评论(二级):root_id 命中任一根,时间正序
    @Select("<script>SELECT * FROM comments WHERE status = 1 AND root_id IN " +
            "<foreach collection='rootIds' item='rid' open='(' separator=',' close=')'>#{rid}</foreach> " +
            "ORDER BY created_at ASC</script>")
    List<Comment> listByRootIds(@Param("rootIds") List<Long> rootIds);

    @Update("UPDATE comments SET status = 0 WHERE id = #{id}")
    int softDelete(@Param("id") Long id);
}
```

- [ ] **Step 4: 创建 CommentView 与 CommentService**

`CommentView`（Lombok `@Data` + `@AllArgsConstructor`）：id/post_id/user_id/parent_id/root_id/content/like_count/status/created_at + `user`(UserView) + `replies`(List<CommentView>)。静态 `from(Comment, UserView, List<CommentView>)`。

`CommentService`：
- `create(Long userId, CommentRequest req)`：post_id 或 content 空→400 `"post_id 和 content 不能为空"`；`postMapper.selectVisibleById(postId)` 空→404 `"帖子不存在"`；若 parent_id 非空，`commentMapper.selectById(parentId)` 空→404 `"父评论不存在"`，rootId=parent.getRootId()==null?parent.getId():parent.getRootId()；insert + `postMapper.changeCommentCount(postId, +1)` → `CommentView.from(评论, authorView(userId), List.of())`
- `listByPost(Long postId, int page, int limit)`：`listRoots` → 取 rootIds → `listByRootIds` 按 root_id 分组挂到对应根评论的 replies → 组装 `PageView<CommentView>` → `{comments,total,page}`（total=countRoots）
- `delete(Long id, Long userId)`：`selectById` 空→404 `"评论不存在"`；非作者→403 `"无权删除此评论"`；softDelete + `postMapper.changeCommentCount(评论.postId, -1)` → `{message:"删除成功"}`

`CommentController`（前缀 /api/comments）：POST（201 {comment}）、GET /post/{postId}（{comments,total,page}）、DELETE /{id}（{message}）。**其 `GET /post/{postId}` 加 `@PublicEndpoint`**（公开列表），并加 `import caigou.caigoupetservice.annotation.PublicEndpoint;`。

- [ ] **Step 5: 运行测试确认通过**

运行：`DB_PASS='chen9911.' ./mvnw test -Dtest=CommentApiIntegrationTest`
Expected: PASS

- [ ] **Step 6: 全量回归**

运行：`DB_PASS='chen9911.' ./mvnw test`
Expected: 全部通过

- [ ] **Step 7: 提交**

```bash
git add src/main/java/caigou/caigoupetservice/entity/Comment.java src/main/java/caigou/caigoupetservice/mapper/CommentMapper.java src/main/java/caigou/caigoupetservice/dto/CommentView.java src/main/java/caigou/caigoupetservice/service/CommentService.java src/main/java/caigou/caigoupetservice/controller/CommentController.java src/test/java/caigou/caigoupetservice/controller/CommentApiIntegrationTest.java
git commit -m "feat: comments 模块——两级树形评论、软删除与计数维护"
```

---

## 批次 1 完成验收清单

- [ ] `DB_PASS='chen9911.' ./mvnw test` 全量通过（auth + schema + resource + user + post + relation + comment）
- [ ] 13 张表已建（SchemaSmokeTest 通过）
- [ ] 社区接口经 `curl`/前端人工验证：帖子发布/列表/详情、点赞收藏关注、评论树
- [ ] 前端主页社区流可用（`npm start` 跑 Electron，首页可加载帖子流）——**此步需前端项目配合，属批次 5 全量联调的前置人工检查**

---

## 遗留事项（记录到收尾批次）

1. 现有认证模块的 record DTO（`LoginResult`/`RegisterRequest`/`UserView`/`ChangePasswordRequest` 等）统一替换为 Lombok 类——批次 5 处理，本批次不动。
2. `PostView`/`ResourceView` 等新 DTO 已用 Lombok（snake_case 字段名），与 record 风格的 `UserView` 并存；批次 5 统一后 `UserView` 改为 Lombok。
3. 前端 `panels/homepage.js`/`plugins.js`/`login/` 硬编码 `localhost:3000` 的清理，属批次 5。
4. `GET /api/resources/files/{filename}` 是否被前端使用，需在批次 5 联调时核对（若用则补静态映射）。
