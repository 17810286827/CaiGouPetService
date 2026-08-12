# chat / socket / pet / plugins 迁移实现计划（P3-P5）

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 把前端 Express 剩余的 chat（5 REST）+ 完整 socket 实时事件 + pet（5 REST）+ plugins（9 REST）迁移到 Spring Boot，每批次前端切回 Java(3000) 并删除对应 Express 模块；Express 最终仅保留 admin + 静态资源。

**架构：** 沿用既有分层（controller 薄/service 厚/mapper 注解 SQL/entity 映射表/dto 出入参/全局异常处理器）。socket 用 netty-socketio（已过 POC），扩展 `SocketConfig` 增加握手鉴权与完整事件，`PetInteractionService` 移植 Express 的 pet-interaction.js。chat 消息幂等靠 `uk_sender_client` 唯一键 → 409。

**技术栈：** Spring Boot 4.0.7 + Java 21 + MyBatis 注解式 + MySQL `caigoupet` + Lombok + netty-socketio 2.0.13。表已建（chat_rooms/chat_room_members/messages/pet_states/pet_visit_settings/plugins/plugin_favorites），无需 DDL。

**规格：** `docs/superpowers/specs/2026-08-12-chat-pet-socket-plugins-migration-design.md`（接口契约权威来源，逐任务引用其小节）。

## Global Constraints

- **分层**：controller 只做参数校验与返回；业务下沉 service，抛 `ApiException(status, 中文)`；`GlobalExceptionHandler` 统一 `{error}`。
- **编码**：新 entity/dto 一律 Lombok（禁 record）；注释率 ≥20% 中文注释。
- **DTO 序列化**：响应字段直接写 snake_case；请求字段 camelCase + `@JsonProperty("snake_case")`。
- **认证**：公开接口 `@PublicEndpoint`；需登录接口不加；socket 握手在 `AuthorizationListener` 校验。
- **测试**：`DB_PASS='chen9911.' ./mvnw test`，测试与被测文件同包（`src/test/java/.../controller/`），`@AfterEach` 前缀清理。
- **分页/契约**：逐接口对齐设计文档第三节/四节/五节（状态码、错误文案、返回字段）。
- **并发/幂等模式**：参照 `LikeService`/`FavoriteService`（`DuplicateKeyException` catch → 幂等返回；`delete()>0` 门）。
- **前端引用**（本计划修改的 gif-viewer 文件）：`src/pet-socket.js`、`src/ipc-handlers.js`、`src/pet-state-sync.js`、`src/server-api.js`。

---

## 批次 A：socket + chat（P3）

### 任务 1：socket 握手鉴权 + token 取路径验证

**文件：**
- 修改：`src/main/java/caigou/caigoupetservice/socket/SocketConfig.java`
- 修改：`socket-poc/test-socket.js`
- 测试：`socket-poc/`（验证脚本，非 JUnit）

- [ ] **步骤 1：先写前端验证脚本（失败）**

修改 `socket-poc/test-socket.js` 的 `connectClient`：auth 载荷改为 `{ token: <有效JWT> }`（不写死 poc-token），并新增一个「无 token 连接应被拒绝」的检查点：

```js
// 用真实注册用户 JWT 验证握手鉴权
const AUTH_TOKEN = process.env.CAIGOPET_TOKEN; // 调用方注入
function connectClient(label, token) {
  const socket = io(SOCKET_URL, { transports: ['websocket'], auth: token ? { token } : {} });
  socket.on('connect_error', (err) => console.log(`[${label}] connect_error:`, err.message));
  return socket;
}
// 主流程:带 token 的 A/B 应 connect 成功;无 token 的 C 应收到 connect_error
```

- [ ] **步骤 2：修改 SocketConfig 的 AuthorizationListener 实现鉴权**

`SocketConfig.socketIOServer()` 的 `config.setAuthorizationListener` 改为：从 `data.getAuthToken()`（Map，含 token）或 `data.getSingleUrlParam("token")` 取 token → `jwtService.parseUserId(token)` → `userMapper.findById` 复核 status=1 → `AuthorizationResult.SUCCESSFUL_AUTHORIZATION` / `FAILED_AUTHORIZATION`。注入 `JwtService`、`UserMapper`（构造器或 @RequiredArgsConstructor）。POC 的打印保留为 debug 日志。

> 若验证发现 `getAuthToken()` 返回 null（POC 已记录风险），回退方案：前端 auth 载荷用 `{ userId, token }` 或 url query `?token=`，`AuthorizationListener` 兼容读取两者。**此任务以脚本输出「带 token PASS / 无 token REJECT」为完成标准。**

- [ ] **步骤 3：启动后端并跑验证脚本**

`env socket.enabled=true DB_PASS='chen9911.' ./mvnw spring-boot:run`（Windows Git Bash 用 `env` 前缀），另终端 `cd socket-poc && node test-socket.js`（`CAIGOPET_TOKEN` 用一个注册用户的 JWT，可用 `node -e` 调 `POST /api/auth/register` 现取）。预期：带 token 的客户端 connect，无 token 的收到 connect_error。

- [ ] **步骤 4：Commit**

```bash
git add src/main/java/caigou/caigoupetservice/socket/SocketConfig.java socket-poc/test-socket.js
git commit -m "feat: socket 握手鉴权——AuthorizationListener 校验 JWT + 用户状态"
```

### 任务 2：socket 完整 chat 事件

**文件：**
- 修改：`src/main/java/caigou/caigoupetservice/socket/SocketConfig.java`

- [ ] **步骤 1：在 SocketConfig 的 addEventListener 区域扩展 chat 事件**

在现有 `chat:join`/`chat:message` 基础上补：`chat:leave`（`client.leaveRoom("room:"+roomId)`）、`chat:typing`（`getRoomOperations("room:"+room_id).sendEvent("chat:typing", {room_id,user_id,nickname})`）、`chat:stop_typing`（同构）、`chat:read`（`sendEvent("chat:read", {room_id,message_id,user_id})`）。`chat:join` 改为 join 后广播 `chat:user_joined`（排除发送者，`sendEvent("chat:user_joined", client, ...)`）。`chat:message` 沿用排除发送者转发。发送者昵称从握手时的 `UserView` 取（鉴权时存到 client 临时属性或重查）。

- [ ] **步骤 2：扩展验证脚本覆盖新事件**

`test-socket.js` 增加：B 监听 `chat:typing`/`chat:user_joined`/`chat:read`，A 依次 emit 后断言 B 收到对应 payload。

- [ ] **步骤 3：跑验证脚本**

`env socket.enabled=true DB_PASS='chen9911.' ./mvnw spring-boot:run` + `node test-socket.js`。预期：所有 chat 事件双向收发 PASS。

- [ ] **步骤 4：Commit**

```bash
git add src/main/java/caigou/caigoupetservice/socket/SocketConfig.java socket-poc/test-socket.js
git commit -m "feat: socket chat 事件全集——join/leave/typing/stop_typing/read/user_joined"
```

### 任务 3：PetInteractionService + pet:interact 事件

**文件：**
- 创建：`src/main/java/caigou/caigoupetservice/service/PetInteractionService.java`
- 修改：`src/main/java/caigou/caigoupetservice/socket/SocketConfig.java`
- 测试：`socket-poc/test-socket.js`

- [ ] **步骤 1：移植 pet-interaction.js 为 PetInteractionService**

创建 `PetInteractionService`（Lombok `@RequiredArgsConstructor`），字段：`ChatRoomMapper`/`ChatRoomMemberMapper`/`MessageMapper`/`PetVisitSettingMapper`/`ResourceMapper`/`UserMapper`（后两个待 chat/pet 任务建好，本任务先不注入未建 mapper，用 `MessageMapper` 落 msg_type=5）。方法：
- `List<Map<String,String>> petActions()`：8 动作 `visit/mischief/high_five/dance/gift/fight/cuddle/kiss`
- `boolean resolveAllow(Boolean global, Boolean roomOverride)`：`roomOverride != null ? roomOverride : global != false`
- `Map<String,Object> handlePetInteract(Long roomId, Long senderId, String actionId, String clientEventId)`：校验 actionId 存在 → 校验 sender 是房间成员 → `resolveAllow`（取 sender 的串门设置 global+room）→ 冷却检查（`MessageMapper` 查该房间该用户最近 msg_type=5 的 created_at，30s 内 → 抛 `cooldown`）→ 落一条 `Message{room_id,sender_id,msg_type=5,content=JSON{action_id,label,event_id}}` → 返回 ack 数据。失败分支返回结构化 code：`INVALID_REQUEST`/`NOT_ALLOWED`/`COOLDOWN(retry_after_ms)`/`SERVER_ERROR`。

- [ ] **步骤 2：SocketConfig 注册 pet:interact 事件**

`addEventListener("pet:interact", Map.class, ...)`：取 `{room_id, action_id, client_event_id}` → 调 `petInteractionService.handlePetInteract` → ok 则 `client.sendEvent("pet:interact_ack", {event_id, cooldown_until})` + `getRoomOperations("room:"+room_id).sendEvent("pet:interact", client, payload)`；失败则 `client.sendEvent("pet:interact_reject", {event_id, code, message, retry_after_ms?})`。注入 `PetInteractionService`。

- [ ] **步骤 3：验证脚本覆盖 pet:interact**

`test-socket.js`：B 监听 `pet:interact`，A emit `pet:interact{room_id,action_id:'visit',client_event_id:'e1'}` → B 收到广播；A 立即再发同 action → A 收到 `pet:interact_reject` code=COOLDOWN。

- [ ] **步骤 4：跑验证脚本**

同前启动 + 运行，预期 pet:interact ack/广播/冷却 reject PASS。

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/caigou/caigoupetservice/service/PetInteractionService.java src/main/java/caigou/caigoupetservice/socket/SocketConfig.java socket-poc/test-socket.js
git commit -m "feat: socket pet:interact 事件——动作/串门允许/30s 冷却/ack+reject"
```

### 任务 4：chat 数据层（entity/mapper）

**文件：**
- 创建：`src/main/java/caigou/caigoupetservice/entity/ChatRoom.java`、`entity/ChatRoomMember.java`、`entity/Message.java`
- 创建：`src/main/java/caigou/caigoupetservice/mapper/ChatRoomMapper.java`、`mapper/ChatRoomMemberMapper.java`、`mapper/MessageMapper.java`

- [ ] **步骤 1：创建三个实体（Lombok @Data）**

`ChatRoom`：id/type(Integer)/name/avatarUrl/createdBy/createdAt/updatedAt。`ChatRoomMember`：id/roomId/userId/role/lastReadMsgId/createdAt。`Message`：id/roomId/senderId/msgType/content/resourceId/replyTo/status/clientMsgId/createdAt（`clientMsgId` 映射 `client_msg_id`）。

- [ ] **步骤 2：创建三个 Mapper**

```java
@Mapper public interface ChatRoomMapper {
    @Insert("INSERT INTO chat_rooms (type, name, avatar_url, created_by) VALUES (#{type}, #{name}, #{avatarUrl}, #{createdBy})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ChatRoom room);
    @Select("SELECT * FROM chat_rooms WHERE id = #{id}") ChatRoom findById(@Param("id") Long id);
    // 私聊幂等:查已存在 type=1 且含指定成员 + 创建者为我 的房间
    @Select("SELECT DISTINCT cr.* FROM chat_rooms cr JOIN chat_room_members m1 ON m1.room_id=cr.id JOIN chat_room_members m2 ON m2.room_id=cr.id " +
            "WHERE cr.type=1 AND cr.created_by=#{creatorId} AND m1.user_id=#{creatorId} AND m2.user_id=#{otherId} LIMIT 1")
    ChatRoom findPrivateRoom(@Param("creatorId") Long creatorId, @Param("otherId") Long otherId);
    @Select("SELECT cr.* FROM chat_rooms cr JOIN chat_room_members m ON m.room_id=cr.id WHERE m.user_id=#{userId} ORDER BY cr.updated_at DESC")
    List<ChatRoom> listByUserId(@Param("userId") Long userId);
}
@Mapper public interface ChatRoomMemberMapper {
    @Insert("INSERT INTO chat_room_members (room_id, user_id, role, last_read_msg_id) VALUES (#{roomId}, #{userId}, #{role}, #{lastReadMsgId})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ChatRoomMember m);
    @Select("SELECT * FROM chat_room_members WHERE room_id=#{roomId} AND user_id=#{userId}") ChatRoomMember find(@Param("roomId") Long roomId, @Param("userId") Long userId);
    @Select("SELECT * FROM chat_room_members WHERE room_id=#{roomId}") List<ChatRoomMember> listByRoom(@Param("roomId") Long roomId);
    @Update("UPDATE chat_room_members SET last_read_msg_id=#{lastReadMsgId} WHERE id=#{id}") int updateLastRead(ChatRoomMember m);
}
@Mapper public interface MessageMapper {
    @Insert("INSERT INTO messages (room_id, sender_id, msg_type, content, resource_id, reply_to, status, client_msg_id) " +
            "VALUES (#{roomId}, #{senderId}, #{msgType}, #{content}, #{resourceId}, #{replyTo}, #{status}, #{clientMsgId})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Message msg); // 重复 client_msg_id 抛 DuplicateKeyException
    @Select("SELECT * FROM messages WHERE room_id=#{roomId} AND status=1 AND id < #{before} ORDER BY id DESC LIMIT #{limit}")
    List<Message> listBefore(@Param("roomId") Long roomId, @Param("before") Long before, @Param("limit") int limit);
    @Select("SELECT * FROM messages WHERE room_id=#{roomId} AND status=1 ORDER BY id DESC LIMIT #{limit}")
    List<Message> listLatest(@Param("roomId") Long roomId, @Param("limit") int limit);
    @Select("SELECT * FROM messages WHERE room_id=#{roomId} AND sender_id=#{senderId} AND msg_type=5 ORDER BY created_at DESC LIMIT 3")
    List<Message> findRecentPetInteract(@Param("roomId") Long roomId, @Param("senderId") Long senderId);
    @Select("SELECT * FROM messages WHERE id=#{id}") Message findById(@Param("id") Long id);
}
```

> 补 `import java.util.List;`、`org.apache.ibatis.annotations.Update;`。`MessageMapper` 供任务 3 与 chat service 共用。

- [ ] **步骤 3：编译校验**

`./mvnw -q compile`。预期：BUILD SUCCESS（暂无测试，仅保证可编译）。

- [ ] **步骤 4：Commit**

```bash
git add src/main/java/caigou/caigoupetservice/entity/ChatRoom.java src/main/java/caigou/caigoupetservice/entity/ChatRoomMember.java src/main/java/caigou/caigoupetservice/entity/Message.java src/main/java/caigou/caigoupetservice/mapper/ChatRoomMapper.java src/main/java/caigou/caigoupetservice/mapper/ChatRoomMemberMapper.java src/main/java/caigou/caigoupetservice/mapper/MessageMapper.java
git commit -m "feat: chat 数据层——ChatRoom/ChatRoomMember/Message 实体与 Mapper"
```

### 任务 5：chat 房间接口（创建/列表）

**文件：**
- 创建：`src/main/java/caigou/caigoupetservice/dto/ChatRoomView.java`
- 创建：`src/main/java/caigou/caigoupetservice/service/ChatService.java`
- 创建：`src/main/java/caigou/caigoupetservice/controller/ChatController.java`
- 测试：`src/test/java/caigou/caigoupetservice/controller/ChatApiIntegrationTest.java`

- [ ] **步骤 1：编写失败测试（房间创建/私聊复用/列表）**

```java
package caigou.caigoupetservice.controller;
// @SpringBootTest + @AutoConfigureMockMvc，PREFIX="testchat_"，@AfterEach 清 chat_room_members/messages/chat_rooms/users
@Test void createRoom_private_shouldReturn201() // 注册 A、B；A POST /api/chat/rooms {type:1,member_ids:[B.id]} → 201，room.type=1
@Test void createRoom_private_duplicate_shouldReuse() // 同 A、B 再 POST → 200 同 room.id（幂等复用）
@Test void listRooms_shouldReturnRooms() // 建一个房间后 GET /api/chat/rooms → rooms 数组含该房间
```

- [ ] **步骤 2：运行确认失败** `DB_PASS='chen9911.' ./mvnw test -Dtest=ChatApiIntegrationTest` → 编译失败。

- [ ] **步骤 3：实现 ChatRoomView + ChatService + ChatController**

`ChatRoomView`（Lombok `@Data`+`@AllArgsConstructor`+静态 `from`）：id/type/name/avatar_url/created_by/created_at/updated_at + `last_message`(可空)。

`ChatService`：
```java
public Map<String,Object> createRoom(Long userId, Map<String,Object> body) {
    int type = ((Number) body.getOrDefault("type", 1)).intValue();
    String name = body.get("name") == null ? "" : String.valueOf(body.get("name"));
    @SuppressWarnings("unchecked") List<Integer> ids = (List<Integer>) body.getOrDefault("member_ids", List.of());
    List<Long> memberIds = ids.stream().map(Long::valueOf).toList();
    if (type == 1 && !memberIds.isEmpty()) {
        ChatRoom existing = chatRoomMapper.findPrivateRoom(userId, memberIds.get(0));
        if (existing != null) return Map.of("room", ChatRoomView.from(existing, lastMessage(existing.getId())));
    }
    ChatRoom room = new ChatRoom(); room.setType(type); room.setName(name); room.setCreatedBy(userId);
    chatRoomMapper.insert(room);
    ChatRoomMember me = new ChatRoomMember(); me.setRoomId(room.getId()); me.setUserId(userId); me.setRole(2); chatRoomMemberMapper.insert(me);
    for (Long mid : memberIds) if (!mid.equals(userId)) { ChatRoomMember m = new ChatRoomMember(); m.setRoomId(room.getId()); m.setUserId(mid); m.setRole(0); chatRoomMemberMapper.insert(m); }
    return Map.of("room", ChatRoomView.from(room, null));
}
public List<ChatRoomView> listRooms(Long userId) { // 每房间查最后一条消息(MessageMapper.listLatest(roomId,1))
    return chatRoomMapper.listByUserId(userId).stream()
        .map(r -> ChatRoomView.from(r, lastMessage(r.getId()))).toList();
}
```

`ChatController`（`/api/chat`）：`POST /rooms`（`Map<String,Object>` body → 201 若 created else 200；用返回值区分较复杂，简化：统一返回 body，controller 不区分 201/200，`@PostMapping("/rooms")` 直接返回 `Map.of("room", ...)`——对齐 Express 复用 200 语义；新建也返回 200 可接受，但契约要求新建 201。实现：`ChatService.createRoom` 返回 `{room, created}`，controller 按 created 定 201/200）。`GET /rooms` 需登录（不加注解）。

- [ ] **步骤 4：运行测试确认通过** `DB_PASS='chen9911.' ./mvnw test -Dtest=ChatApiIntegrationTest` → PASS。

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/caigou/caigoupetservice/dto/ChatRoomView.java src/main/java/caigou/caigoupetservice/service/ChatService.java src/main/java/caigou/caigoupetservice/controller/ChatController.java src/test/java/caigou/caigoupetservice/controller/ChatApiIntegrationTest.java
git commit -m "feat: chat 房间接口——创建(私聊幂等复用)/列表(含最后消息)"
```

### 任务 6：chat 消息接口（发送/历史/房间详情）

**文件：**
- 修改：`src/main/java/caigou/caigoupetservice/service/ChatService.java`
- 修改：`src/main/java/caigou/caigoupetservice/controller/ChatController.java`
- 测试：`src/test/java/caigou/caigoupetservice/controller/ChatApiIntegrationTest.java`

- [ ] **步骤 1：追加失败测试（消息/详情）**

```java
@Test void sendMessage_shouldReturn201() // 建房间；POST /api/chat/messages {room_id, content, client_msg_id:'m1'} → 201，message.sender 内嵌
@Test void sendMessage_duplicateClientMsgId_shouldReturn409() // 同 client_msg_id 再发 → 409「消息重复」
@Test void sendMessage_notMember_shouldReturn403() // 非成员用户发 → 403「不在聊天室中」
@Test void getMessages_shouldReturnHistoryAscending() // 发 2 条；GET /rooms/:id/messages → messages 升序 2 条
@Test void getMessages_before_shouldPage() // before=第2条id → 只返回更早的
@Test void roomDetail_shouldReturnMembers() // GET /rooms/:id → room + members 数组
```

- [ ] **步骤 2：运行确认失败。**

- [ ] **步骤 3：实现消息发送/历史/详情**

`ChatService` 追加：
```java
public MessageView sendMessage(Long userId, Map<String,Object> body) { // 见契约：room_id/client_msg_id 必填400；成员校验403
    Long roomId = body.get("room_id") == null ? null : Long.valueOf(String.valueOf(body.get("room_id")));
    String clientMsgId = body.get("client_msg_id") == null ? null : String.valueOf(body.get("client_msg_id"));
    if (roomId == null || clientMsgId == null) throw new ApiException(400, "room_id 和 client_msg_id 不能为空");
    if (chatRoomMemberMapper.find(roomId, userId) == null) throw new ApiException(403, "不在聊天室中");
    Message msg = new Message(); msg.setRoomId(roomId); msg.setSenderId(userId);
    msg.setMsgType(body.get("msg_type") == null ? 0 : ((Number) body.get("msg_type")).intValue());
    msg.setContent(body.get("content") == null ? "" : String.valueOf(body.get("content")));
    if (body.get("resource_id") != null) msg.setResourceId(Long.valueOf(String.valueOf(body.get("resource_id"))));
    if (body.get("reply_to") != null) msg.setReplyTo(Long.valueOf(String.valueOf(body.get("reply_to"))));
    msg.setStatus(1); msg.setClientMsgId(clientMsgId);
    try { messageMapper.insert(msg); }
    catch (org.springframework.dao.DuplicateKeyException e) { throw new ApiException(409, "消息重复"); }
    return MessageView.from(msg, userView(userId), null); // sender 内嵌；socket 推送见步骤4
}
public List<MessageView> getMessages(Long userId, Long roomId, Long before, int limit) { // 成员403；before 空则 latest；升序返回
    if (chatRoomMemberMapper.find(roomId, userId) == null) throw new ApiException(403, "不在聊天室中");
    List<Message> rows = before != null ? messageMapper.listBefore(roomId, before, limit) : messageMapper.listLatest(roomId, limit);
    Collections.reverse(rows);
    return rows.stream().map(m -> MessageView.from(m, userView(m.getSenderId()), null)).toList();
}
public Map<String,Object> roomDetail(Long userId, Long roomId) { // 成员403；{room, members}
    if (chatRoomMemberMapper.find(roomId, userId) == null) throw new ApiException(403, "不在该房间中");
    return Map.of("room", ChatRoomView.from(chatRoomMapper.findById(roomId), null),
        "members", chatRoomMemberMapper.listByRoom(roomId).stream().map(m -> userView(m.getUserId())).toList());
}
```
创建 `MessageView`（id/room_id/sender_id/msg_type/content/resource_id/reply_to/status/client_msg_id/created_at + `sender` 内嵌 UserView）。`ChatController` 追加：`POST /messages`（201）、`GET /rooms/{roomId}/messages`（query before/limit=50）、`GET /rooms/{roomId}`。更新已读：`getMessages` 成功后 `chatRoomMemberMapper.find` 更新 `lastReadMsgId = max(last, 最新消息id)`。

> **socket 推送**：`sendMessage` 成功后若注入 `SocketIOServer`，`getRoomOperations("room:"+roomId).sendEvent("chat:message", 消息Map)`。本任务若 `SocketConfig` 的 server 未暴露，先跳过推送（任务 2 已让 SocketConfig 转发 `chat:message`；REST 落库后的实时推送可后续在批次 A 收尾补齐，或在 `SocketConfig` 暴露 `getServer()`）。

- [ ] **步骤 4：运行测试确认通过。**

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/caigou/caigoupetservice/dto/MessageView.java src/main/java/caigou/caigoupetservice/service/ChatService.java src/main/java/caigou/caigoupetservice/controller/ChatController.java src/test/java/caigou/caigoupetservice/controller/ChatApiIntegrationTest.java
git commit -m "feat: chat 消息接口——发送(幂等409)/历史(游标+已读)/房间详情+成员"
```

### 任务 7：批次 A 前端切换 + 删 Express chat/socket

**文件：**
- 修改：`gif-viewer/src/pet-socket.js`
- 修改：`gif-viewer/src/ipc-handlers.js`
- 删除：`CaiGouPet/server/src/routes/chat.js`
- 修改：`CaiGouPet/server/src/index.js`（移除 chat 挂载）

- [ ] **步骤 1：前端 socket 与 chat REST 切回 Java**

`pet-socket.js`：`io('http://localhost:3100')` → `io('http://localhost:3000')`（注释同步改，说明 socket 已由 Java netty-socketio 提供）。`ipc-handlers.js`：chat 5 个 `apiRequest(..., serverApi.EXPRESS_PORT)` 去掉 `EXPRESS_PORT` 参数（默认 3000）。

- [ ] **步骤 2：删 Express chat 路由**

删除 `server/src/routes/chat.js`；`server/src/index.js` 移除 `const chatRoutes = require('./routes/chat');` 与 `app.use('/api/chat', ...)`；`node --check server/src/index.js` 通过。

- [ ] **步骤 3：前端语法校验**

`node --check gif-viewer/src/pet-socket.js`、`node --check gif-viewer/src/ipc-handlers.js`。

- [ ] **步骤 4：Commit（前端分支 front-refactor + 后端合并前验证）**

```bash
# 前端仓
cd D:/IDE/project/CaiGouPet && git add gif-viewer/src/pet-socket.js gif-viewer/src/ipc-handlers.js server/src/index.js server/src/routes/chat.js
git commit -m "feat: chat/socket 切回 Java(3000)——移除 Express chat 路由"
```

---

## 批次 B：pet（P4）

### 任务 8：pet 数据层 + 状态接口

**文件：**
- 创建：`src/main/java/caigou/caigoupetservice/entity/PetState.java`、`entity/PetVisitSetting.java`
- 创建：`src/main/java/caigou/caigoupetservice/mapper/PetStateMapper.java`、`mapper/PetVisitSettingMapper.java`
- 创建：`src/main/java/caigou/caigoupetservice/service/PetService.java`
- 创建：`src/main/java/caigou/caigoupetservice/controller/PetController.java`
- 测试：`src/test/java/caigou/caigoupetservice/controller/PetApiIntegrationTest.java`

- [ ] **步骤 1：编写失败测试（状态获取/同步）**

```java
@Test void getState_shouldCreateDefault() // GET /api/pet → 200，pet_state.user_id 有值，emotion_state/personality 非 null
@Test void syncState_shouldUpsert() // PUT /api/pet/sync {emotion_state:{joy:0.5},personality:{}} → pet_state.emotion_state.joy=0.5；再 sync 新值 → 覆盖
```

- [ ] **步骤 2：运行确认失败。**

- [ ] **步骤 3：实现实体/Mapper/Service/Controller**

`PetState`：id/userId/emotionState(String JSON)/personality(String JSON)/lastSyncAt/createdAt/updatedAt。`PetVisitSetting`：id/userId/roomId/allow/createdAt/updatedAt。

```java
@Mapper interface PetStateMapper {
    @Select("SELECT * FROM pet_states WHERE user_id=#{userId}") PetState findByUserId(@Param("userId") Long userId);
    @Insert("INSERT INTO pet_states (user_id, emotion_state, personality, last_sync_at) VALUES (#{userId}, #{emotionState}, #{personality}, #{lastSyncAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id") int insert(PetState s);
    @Update("UPDATE pet_states SET emotion_state=#{emotionState}, personality=#{personality}, last_sync_at=#{lastSyncAt} WHERE id=#{id}") int update(PetState s);
}
@Mapper interface PetVisitSettingMapper {
    @Select("SELECT * FROM pet_visit_settings WHERE user_id=#{userId} AND room_id IS NULL") PetVisitSetting findGlobal(@Param("userId") Long userId);
    @Select("SELECT * FROM pet_visit_settings WHERE user_id=#{userId} AND room_id=#{roomId}") PetVisitSetting findRoom(@Param("userId") Long userId, @Param("roomId") Long roomId);
    @Select("SELECT * FROM pet_visit_settings WHERE user_id=#{userId}") List<PetVisitSetting> listByUser(@Param("userId") Long userId);
    @Insert("INSERT INTO pet_visit_settings (user_id, room_id, allow) VALUES (#{userId}, #{roomId}, #{allow})")
    @Options(useGeneratedKeys = true, keyProperty = "id") int insert(PetVisitSetting s);
    @Update("UPDATE pet_visit_settings SET allow=#{allow} WHERE id=#{id}") int update(PetVisitSetting s);
    @Delete("DELETE FROM pet_visit_settings WHERE user_id=#{userId} AND room_id=#{roomId}") int deleteRoom(@Param("userId") Long userId, @Param("roomId") Long roomId);
}
```

`PetService`：`getState(userId)`（无则建默认 `{}`）、`syncState(userId, emotionJson, personalityJson)`（upsert + last_sync_at=now）。`PetController`（`/api/pet`，全需登录）：`GET /` → `{pet_state}`；`PUT /sync` body `{emotion_state, personality}`（原样 JSON 存取，不解析对象）。

- [ ] **步骤 4：运行测试确认通过。**

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/caigou/caigoupetservice/entity/PetState.java src/main/java/caigou/caigoupetservice/entity/PetVisitSetting.java src/main/java/caigou/caigoupetservice/mapper/PetStateMapper.java src/main/java/caigou/caigoupetservice/mapper/PetVisitSettingMapper.java src/main/java/caigou/caigoupetservice/service/PetService.java src/main/java/caigou/caigoupetservice/controller/PetController.java src/test/java/caigou/caigoupetservice/controller/PetApiIntegrationTest.java
git commit -m "feat: pet 模块——状态获取(默认创建)/同步"
```

### 任务 9：pet 串门设置接口

**文件：**
- 修改：`src/main/java/caigou/caigoupetservice/service/PetService.java`
- 修改：`src/main/java/caigou/caigoupetservice/controller/PetController.java`
- 修改：`src/main/java/caigou/caigoupetservice/service/PetInteractionService.java`（复用 resolveAllow）
- 测试：`src/test/java/caigou/caigoupetservice/controller/PetApiIntegrationTest.java`

- [ ] **步骤 1：追加失败测试（串门设置）**

```java
@Test void visitSettings_global_shouldReturnSettings() // PUT /api/pet/visit-settings {allow:false} → {global:false}；GET → settings.global=false
@Test void visitSettings_room_shouldUpsertAndDelete() // 建房间+加入 A、B；B PUT room/:id {allow:false} → {room_id,allow:false}；A GET /api/pet/visit-settings?room_id= → other_allow=false；B PUT {allow:null} → 删除；A 再查 → other_allow=true
@Test void visitSettings_room_notMember_should403() // 非成员 C PUT room/:id → 403「不在该房间中」
```

- [ ] **步骤 2：运行确认失败。**

- [ ] **步骤 3：实现串门设置**

`PetService` 追加：`getVisitSettings(userId, roomId)`（全局+房间列表 → `{settings:{global, rooms}}`；roomId 时校验成员（`ChatRoomMemberMapper.find`，403「不在该房间中」）+ 查对方（`PetVisitSettingMapper.findGlobal/findRoom` on otherId）→ `other_allow = resolveAllow(...)`）、`setGlobal(userId, allow)`（boolean 校验 400「allow 必须是布尔值」；upsert → `{global}`）、`setRoom(userId, roomId, allow)`（成员校验 403；null 删除 / 否则 upsert → `{room_id, allow}`）。`PetInteractionService.resolveAllow` 提为 `public static` 或在 `PetService` 内复用小实现。

- [ ] **步骤 4：运行测试确认通过。**

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/caigou/caigoupetservice/service/PetService.java src/main/java/caigou/caigoupetservice/controller/PetController.java src/main/java/caigou/caigoupetservice/service/PetInteractionService.java src/test/java/caigou/caigoupetservice/controller/PetApiIntegrationTest.java
git commit -m "feat: pet 串门设置——全局/房间覆盖/对方允许状态/删除覆盖"
```

### 任务 10：批次 B 前端切换 + 删 Express pet

**文件：**
- 修改：`gif-viewer/src/ipc-handlers.js`、`gif-viewer/src/pet-state-sync.js`
- 删除：`CaiGouPet/server/src/routes/pet.js`
- 修改：`CaiGouPet/server/src/index.js`

- [ ] **步骤 1：前端 pet REST 切回 Java**

`ipc-handlers.js`：pet 3 个 `apiRequest(..., EXPRESS_PORT)` 去掉参数；`pet-state-sync.js`：`apiRequest('PUT','/api/pet/sync', ..., EXPRESS_PORT)` 去掉 `EXPRESS_PORT`。

- [ ] **步骤 2：删 Express pet 路由**

删除 `server/src/routes/pet.js`；`index.js` 移除 require + `app.use('/api/pet', ...)`；`node --check`。

- [ ] **步骤 3：Commit（前端仓）**

```bash
cd D:/IDE/project/CaiGouPet && git add gif-viewer/src/ipc-handlers.js gif-viewer/src/pet-state-sync.js server/src/index.js server/src/routes/pet.js
git commit -m "feat: pet 切回 Java(3000)——移除 Express pet 路由"
```

---

## 批次 C：plugins（P5）

### 任务 11：plugins 数据层 + 列表/详情/categories/my

**文件：**
- 创建：`src/main/java/caigou/caigoupetservice/entity/Plugin.java`、`entity/PluginFavorite.java`
- 创建：`src/main/java/caigou/caigoupetservice/mapper/PluginMapper.java`、`mapper/PluginFavoriteMapper.java`
- 创建：`src/main/java/caigou/caigoupetservice/dto/PluginView.java`
- 创建：`src/main/java/caigou/caigoupetservice/service/PluginService.java`
- 创建：`src/main/java/caigou/caigoupetservice/controller/PluginController.java`
- 测试：`src/test/java/caigou/caigoupetservice/controller/PluginApiIntegrationTest.java`

- [ ] **步骤 1：编写失败测试（列表/详情/categories/my）**

```java
// 测试需先造一条插件数据(直接 jdbc INSERT 或后续 upload 接口);造数据用 jdbc 便于隔离
@Test void list_shouldReturnPagination() // jdbc 插 1 条 status=1；GET /api/plugins?limit=10 → plugins[0]、pagination.total>=1
@Test void list_sortInvalid_shouldFallback() // sort=xxx → 默认 download_count 排序不报错
@Test void categories_shouldReturnList() // GET /api/plugins/categories → categories 数组非空
@Test void detail_shouldReturnIsFavorited() // 带 token 查详情 → plugin.isFavorited 为 boolean
@Test void my_shouldReturnOwn() // 需登录；GET /api/plugins/my → 数组
```

- [ ] **步骤 2：运行确认失败。**

- [ ] **步骤 3：实现实体/Mapper/View/Service/Controller**

`Plugin`：id/name/version/description/authorId/category/tags/icon/downloadCount/favoriteCount/manifestJson/filePath/fileSize/status/reviewComment/createdAt/updatedAt。`PluginFavorite`：id/userId/pluginId/createdAt。

```java
@Mapper interface PluginMapper {
    @Select("SELECT * FROM plugins WHERE id=#{id}") Plugin findById(@Param("id") Long id);
    @Select("<script>SELECT * FROM plugins WHERE status=1" +
        "<if test='category != null'> AND category=#{category}</if>" +
        "<if test='search != null and search != \"\"'> AND (name LIKE CONCAT('%',#{search},'%') OR description LIKE CONCAT('%',#{search},'%') OR tags LIKE CONCAT('%',#{search},'%'))</if>" +
        " ORDER BY ${sortField} ${sortOrder} LIMIT #{offset}, #{limit}</script>")
    List<Plugin> list(@Param("category") String category, @Param("search") String search, @Param("sortField") String sortField, @Param("sortOrder") String sortOrder, @Param("offset") int offset, @Param("limit") int limit);
    @Select("<script>SELECT COUNT(*) FROM plugins WHERE status=1" + /* 同 where 条件 */ "</script>") long count(...);
    @Select("SELECT * FROM plugins WHERE author_id=#{authorId} ORDER BY created_at DESC") List<Plugin> listByAuthor(@Param("authorId") Long authorId);
    @Select("SELECT * FROM plugins WHERE name=#{name} AND author_id=#{authorId}") Plugin findByNameAndAuthor(@Param("name") String name, @Param("authorId") Long authorId);
    @Insert("INSERT INTO plugins (name, version, description, author_id, category, tags, icon, manifest_json, file_path, file_size, status) VALUES (...)") @Options(useGeneratedKeys=true, keyProperty="id") int insert(Plugin p);
    @Update("UPDATE plugins SET version=#{version}, description=#{description}, category=#{category}, tags=#{tags}, icon=#{icon}, manifest_json=#{manifestJson}, file_path=#{filePath}, file_size=#{fileSize}, status=1 WHERE id=#{id}") int updateByManifest(Plugin p);
    @Update("UPDATE plugins SET download_count=download_count+1 WHERE id=#{id}") int incrementDownload(@Param("id") Long id);
    @Update("UPDATE plugins SET favorite_count=favorite_count+1 WHERE id=#{id}") int incrementFavorite(@Param("id") Long id);
    @Update("UPDATE plugins SET favorite_count=favorite_count-1 WHERE id=#{id}") int decrementFavorite(@Param("id") Long id);
    @Delete("DELETE FROM plugins WHERE id=#{id}") int deleteById(@Param("id") Long id);
}
@Mapper interface PluginFavoriteMapper {
    @Select("SELECT * FROM plugin_favorites WHERE user_id=#{userId} AND plugin_id=#{pluginId}") PluginFavorite find(@Param("userId") Long userId, @Param("pluginId") Long pluginId);
    @Insert("INSERT INTO plugin_favorites (user_id, plugin_id) VALUES (#{userId}, #{pluginId})") @Options(useGeneratedKeys=true, keyProperty="id") int insert(PluginFavorite f);
    @Delete("DELETE FROM plugin_favorites WHERE user_id=#{userId} AND plugin_id=#{pluginId}") int deleteByUserPlugin(@Param("userId") Long userId, @Param("pluginId") Long pluginId);
    @Delete("DELETE FROM plugin_favorites WHERE plugin_id=#{pluginId}") int deleteByPlugin(@Param("pluginId") Long pluginId);
}
```

`PluginView`（snake_case + `author` 内嵌 UserView + `isFavorited`）。`PluginService`：`list(page,limit,sort,order,category,search)`（sort 白名单 `download_count/favorite_count/created_at/name/version`，order 仅 ASC/DESC；category 白名单校验；返回 `{plugins, pagination:{page,limit,total,totalPages}}`）、`categories()`（静态 `List.of("tool", ...)` 对齐 `VALID_CATEGORIES`）、`detail(id, userId?)`（404「Plugin not found」；带 userId 查 isFavorited）、`listMy(userId)`。`PluginController`（`/api/plugins`）：列表/`categories`/`/:id` 公开（`@PublicEndpoint`），`/my` 需登录。

- [ ] **步骤 4：运行测试确认通过。**

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/caigou/caigoupetservice/entity/Plugin.java src/main/java/caigou/caigoupetservice/entity/PluginFavorite.java src/main/java/caigou/caigoupetservice/mapper/PluginMapper.java src/main/java/caigou/caigoupetservice/mapper/PluginFavoriteMapper.java src/main/java/caigou/caigoupetservice/dto/PluginView.java src/main/java/caigou/caigoupetservice/service/PluginService.java src/main/java/caigou/caigoupetservice/controller/PluginController.java src/test/java/caigou/caigoupetservice/controller/PluginApiIntegrationTest.java
git commit -m "feat: plugins 列表/详情/categories/my + 分页排序过滤"
```

### 任务 12：plugins 上传（zip + manifest 校验）

**文件：**
- 创建：`src/main/java/caigou/caigoupetservice/util/PluginValidator.java`
- 修改：`src/main/java/caigou/caigoupetservice/service/PluginService.java`
- 修改：`src/main/java/caigou/caigoupetservice/controller/PluginController.java`
- 测试：`src/test/java/caigou/caigoupetservice/controller/PluginApiIntegrationTest.java`

- [ ] **步骤 1：编写失败测试（上传校验）**

```java
@Test void upload_missingManifest_should400() // 传一个不含 manifest.json 的 zip → 400
@Test void upload_validZip_should201() // 含合法 manifest.json 的 zip → 201，plugin.name 匹配
@Test void upload_duplicateName_shouldUpdate() // 同名再传 → 200 更新，版本更新
```

> 测试 zip 构造：`ZipOutputStream` 内存生成（`java.util.zip`），manifest.json 内容为 `{"name":"t","version":"1.0.0","category":"tool","manifest":{"name":"t","version":"1.0.0"}}` 对齐 `REQUIRED_MANIFEST_FIELDS`（实现时按 plugin-validator.js 逐字段核对）。

- [ ] **步骤 2：运行确认失败。**

- [ ] **步骤 3：实现 PluginValidator + 上传**

`PluginValidator`（静态方法，复刻 plugin-validator.js）：`VALID_CATEGORIES`、`REQUIRED_MANIFEST_FIELDS`、`validate(manifestJson, zipFiles)` 返回 `{valid, errors[], warnings[]}`（校验必填字段存在、category 合法、manifest 引用的文件在 zip 内）。

`PluginService.upload(userId, MultipartFile file)`：非 .zip → 400；10MB 上限（`MaxUploadSizeExceededException` 已全局 413）；`ZipInputStream` 解压找 `manifest.json` 解析（无 → 400「manifest.json not found in plugin package」；非 JSON → 400）；`PluginValidator.validate` 失败 → 400 带 details/warnings；`findByNameAndAuthor` 同名 → 更新 200 / 新建 201；文件落盘 `uploads/plugins/{uuid}.zip`。`PluginController` 加 `POST /upload`（multipart `file`，需登录）。

- [ ] **步骤 4：运行测试确认通过。**

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/caigou/caigoupetservice/util/PluginValidator.java src/main/java/caigou/caigoupetservice/service/PluginService.java src/main/java/caigou/caigoupetservice/controller/PluginController.java src/test/java/caigou/caigoupetservice/controller/PluginApiIntegrationTest.java
git commit -m "feat: plugins 上传——zip 解压 + manifest 校验 + 同名更新"
```

### 任务 13：plugins 下载/收藏/删除/收藏列表

**文件：**
- 修改：`src/main/java/caigou/caigoupetservice/service/PluginService.java`
- 修改：`src/main/java/caigou/caigoupetservice/controller/PluginController.java`
- 测试：`src/test/java/caigou/caigoupetservice/controller/PluginApiIntegrationTest.java`

- [ ] **步骤 1：追加失败测试（下载/收藏/删除）**

```java
@Test void download_shouldIncrementCountAndReturnFile() // 先 upload 造数据；POST /:id/download → download_count+1，响应为 application/zip 流
@Test void favorite_toggle() // 带 token POST /:id/favorite → favorited:true；再 POST → false
@Test void delete_own_shouldSucceed() // 作者删 → {message:'Plugin deleted'}；再查 404
@Test void delete_notOwner_should403() // 非作者删 → 403
@Test void favoritesList_shouldReturnMine() // 收藏后 GET /api/plugins/favorites/list → 含该插件
```

- [ ] **步骤 2：运行确认失败。**

- [ ] **步骤 3：实现下载/收藏/删除/收藏列表**

`PluginService` 追加：`download(id)`（404；`incrementDownload`；返回 `{filePath, fileName, downloadCount}`，controller 用 `ResponseEntity<Resource>` 流式返回 `name-vversion.zip`）、`toggleFavorite(userId, id)`（404；find 判断 toggle；`incrementFavorite/decrementFavorite`；`{favorited, favorite_count}`）、`deletePlugin(userId, id)`（404；非作者 403「You can only delete your own plugins」；删收藏+行+磁盘文件）、`listFavorites(userId)`（join 查收藏的插件 → `{favorites}`）。`PluginController` 加：`POST /:id/download`（公开，返回文件流）、`POST /:id/favorite`（需登录）、`DELETE /:id`（需登录）、`GET /favorites/list`（需登录）。注意路由顺序：`GET /favorites/list` 必须在 `GET /:id` 之前注册，避免被 `:id` 吞。

- [ ] **步骤 4：运行测试确认通过。**

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/caigou/caigoupetservice/service/PluginService.java src/main/java/caigou/caigoupetservice/controller/PluginController.java src/test/java/caigou/caigoupetservice/controller/PluginApiIntegrationTest.java
git commit -m "feat: plugins 下载/收藏 toggle/删除/收藏列表"
```

### 任务 14：批次 C 前端 + 删 Express plugins

**文件：**
- 删除：`CaiGouPet/server/src/routes/plugins.js`
- 修改：`CaiGouPet/server/src/index.js`

- [ ] **步骤 1：删 Express plugins 路由**

删除 `server/src/routes/plugins.js`；`index.js` 移除 require + `app.use('/api/plugins', ...)`；`node --check`。前端 plugins 面板 `API_BASE = 'http://localhost:3000/api'` 已指向 Java，无需改。

- [ ] **步骤 2：Commit（前端仓）**

```bash
cd D:/IDE/project/CaiGouPet && git add server/src/index.js server/src/routes/plugins.js
git commit -m "feat: plugins 切回 Java(3000)——移除 Express plugins 路由"
```

---

## 收尾

### 任务 15：全量回归 + 一致性核对

**文件：**
- 修改：全量测试运行

- [ ] **步骤 1：后端全量测试**

`DB_PASS='chen9911.' ./mvnw test`。预期：既有 52 个 + 新增 chat/pet/plugins 全部通过，无回归。

- [ ] **步骤 2：Express 残留核对**

`grep -rn "require.*routes" CaiGouPet/server/src/index.js` → 仅 admin；`ls CaiGouPet/server/src/routes/` → 仅 `admin.js`。

- [ ] **步骤 3：端口核对**

启动 Java(3000) + Express(3100)：chat/pet/plugins/socket 请求全部命中 Java，Express 仅 admin/health/静态。`curl -s -o /dev/null -w '%{http_code}' http://localhost:3000/api/chat/rooms`（带 token）→ 200 或 401（Java 处理）；`http://localhost:3100/api/chat/rooms` → 404（Express 已删）。

- [ ] **步骤 4：Commit（如测试/代码有修正）**

---

## 附：文件结构总览

```
后端新增:
  entity/   ChatRoom ChatRoomMember Message PetState PetVisitSetting Plugin PluginFavorite
  mapper/   ChatRoomMapper ChatRoomMemberMapper MessageMapper PetStateMapper PetVisitSettingMapper PluginMapper PluginFavoriteMapper
  service/  ChatService PetService PetInteractionService PluginService
  controller/ ChatController PetController PluginController
  dto/      ChatRoomView MessageView PluginView
  util/     PluginValidator
  socket/   SocketConfig(扩展:鉴权+chat事件+pet:interact)
前端修改:
  gif-viewer/src/ pet-socket.js ipc-handlers.js pet-state-sync.js
前端删除:
  CaiGouPet/server/src/routes/ chat.js pet.js plugins.js
```

> **批次依赖**：任务 3（PetInteractionService）依赖任务 4 的 MessageMapper 落库；任务 9 串门复用任务 4 的 ChatRoomMemberMapper。若按顺序执行，任务 3 可在任务 4 之后做（调整顺序无妨，Mapper 已在本计划定义）。任务 5/6/8/9/11/13 的 controller/service 均按「先写测试 → 跑失败 → 实现 → 跑通过」TDD 进行。
