# 接口迁移批次 3-5：chat / socket / pet / plugins 设计文档

> 文档版本：v1.0（2026-08-12）
> 头脑风暴产出，经用户评审批准。范围：chat + pet + socket + plugins 迁移到 Java 后端（admin 排除，留在 Express）。

## 一、背景与目标

延续 `2026-08-11-p1-social-module-migration-design.md` 的迁移路线图。P1（users/follows/likes/favorites）与 P2（posts/resources/comments）已迁移完成，前端已切到 Java(3000)。本批次迁移剩余的桌宠主流程模块：

- **P3**：完整 socket 实时事件 + chat 聊天模块
- **P4**：pet 宠物模块
- **P5**：plugins 插件市场（admin 不在本批次）

**已确认决策**：
- 历史数据（Express SQLite 中的聊天/宠物/插件数据）**不迁移**，Java 的 MySQL 表已建好，从零开始
- 前端每批次迁移后从 Express(3100) 切回 Java(3000)；socket 从 Express 切回 Java
- 迁移后 Express 仅保留 admin 管理后台 + 静态资源

## 二、批次划分

每批次交付链路：**Java 实现 → 集成测试对齐 Express → 前端切回 Java → 删除对应 Express 模块**。

| 批次 | 内容 | 接口数 |
|---|---|---|
| A（P3） | socket 完整事件 + 握手鉴权 + chat REST | 5 REST + 7 socket 事件 |
| B（P4） | pet REST | 5 REST |
| C（P5） | plugins REST | 9 REST |

## 三、批次 A：socket + chat

### 3.1 socket 握手鉴权（第一步验证）

netty-socketio `AuthorizationListener` 中从握手载荷取 token（`data.getAuthToken()` 或查询参数），调 `JwtService.parseUserId` 校验并查库复核用户 status=1。**POC 发现 `getAuthToken()` 返回 null 风险**——先写最小连通验证脚本（socket-poc 复用），确认 v4 客户端 `auth:{token}` 的存取路径；不通则前端改用 `query: {token}` 或 `auth: {userId, token}` 调整后验证。

### 3.2 socket 事件（扩展 SocketConfig + PetInteractionService）

| 事件 | 入参 | 行为（对齐 Express socket/index.js） |
|---|---|---|
| chat:join | roomId | 加入 room:{roomId}，广播 chat:user_joined{user_id,nickname,avatar_url} |
| chat:leave | roomId | 离开 room |
| chat:typing | {room_id} | 广播 chat:typing{room_id,user_id,nickname} |
| chat:stop_typing | {room_id} | 广播 chat:stop_typing{room_id,user_id} |
| chat:read | {room_id,message_id} | 广播 chat:read{room_id,message_id,user_id} |
| chat:message | {room_id,...} | 转发给同房间其它客户端（不含发送者） |
| pet:interact | {room_id,action_id,client_event_id} | 校验参数 → 串门允许 → 30s 冷却 → 落 msg_type=5 消息 → ack/reject + 广播 pet:interact |
| disconnect | — | 记录断开 |

**PetInteractionService**（移植 `server/src/services/pet-interaction.js`，142 行）：
- 8 种动作：visit/mischief/high_five/dance/gift/fight/cuddle/kiss
- `resolveAllow(global, roomOverride)`：roomOverride 优先，否则 global（默认允许）
- 冷却 30s：查该房间该用户最近 msg_type=5 消息
- 失败分支：参数不完整 `INVALID_REQUEST`、串门被拒 `NOT_ALLOWED`、冷却中 `COOLDOWN`（带 retry_after_ms）、服务异常 `SERVER_ERROR`

### 3.3 chat REST 接口（前缀 /api/chat，契约对齐 chat.js）

**实体**：ChatRoom/ChatRoomMember/Message（表已建）。消息 `client_msg_id` 唯一键 `uk_sender_client` → 重复插入 `DuplicateKeyException` → **409「消息重复」**。

| 方法 | 路径 | 认证 | 契约要点 |
|---|---|---|---|
| POST | /rooms | 需登录 | body{type,name,member_ids}；type=1 私聊查已有房间双向幂等复用（200 {room}）；否则建 room + 创建者成员(role=2)+其他成员(role=0) → 201 {room} |
| GET | /rooms | 需登录 | 当前用户所有房间（含最后一条消息 last_message）→ {rooms:[...]} |
| POST | /messages | 需登录 | body{room_id,msg_type,content,resource_id,reply_to,client_msg_id}；room_id 或 client_msg_id 缺 → 400「room_id 和 client_msg_id 不能为空」；非成员 → 403「不在聊天室中」；重复 client_msg_id → 409「消息重复」；成功 201 {message}（含 sender + resource），并 socket 推送 chat:message |
| GET | /rooms/:roomId/messages | 需登录 | query{before,limit=50}；非成员 403；where room_id+status=1 + id<before；更新成员 last_read_msg_id；返回升序 {messages:[...]} |
| GET | /rooms/:roomId | 需登录 | 非成员 403「不在该房间中」；{room, members} |

### 3.4 测试（批次 A）

- `src/test/java/.../controller/ChatApiIntegrationTest.java`：房间创建/私聊幂等复用/成员校验 403/发消息幂等 409/消息历史游标/已读更新
- socket 事件：`socket-poc` 扩展或独立测试脚本验证 join/typing/message 转发/pet:interact ack-reject（含冷却）

### 3.5 前端切换（批次 A）

- `pet-socket.js`：socket 连接从 3100 切回 Java(3000)
- `ipc-handlers.js`：chat 5 个调用去掉 `EXPRESS_PORT` 参数（默认 3000）
- 删除 Express `routes/chat.js` + `socket/index.js` 中 chat/pet:interact 事件（保留 admin 相关）

## 四、批次 B：pet（前缀 /api/pet，契约对齐 pet.js）

**实体**：PetState/PetVisitSetting（表已建）。

| 方法 | 路径 | 认证 | 契约要点 |
|---|---|---|---|
| GET | / | 需登录 | 无状态则建默认 {} → {pet_state} |
| PUT | /sync | 需登录 | body{emotion_state,personality}；upsert + last_sync_at → {pet_state} |
| GET | /visit-settings | 需登录 | query{room_id?}；{settings:{global,rooms}}；room_id 时校验成员（403「不在该房间中」）+ other_allow=resolveAllow(对方 global/room) |
| PUT | /visit-settings | 需登录 | body{allow} 必须 boolean（否则 400「allow 必须是布尔值」）；upsert 全局 → {global} |
| PUT | /visit-settings/room/:roomId | 需登录 | 非成员 403；body{allow} boolean 或 null（null=删除覆盖）；{room_id, allow} |

测试：`PetApiIntegrationTest`（状态默认创建/同步/串门设置全局+房间/403）。前端：`pet-state-sync.js` + `ipc-handlers.js` pet 调用去掉 EXPRESS_PORT。删 Express `routes/pet.js`。

## 五、批次 C：plugins（前缀 /api/plugins，契约对齐 plugins.js）

**实体**：Plugin/PluginFavorite（表已建）。文件落盘 `uploads/plugins/`。

| 方法 | 路径 | 认证 | 契约要点 |
|---|---|---|---|
| GET | / | 公开 | query{page=1,limit=20,sort=download_count,order=DESC,category?,search?}；sort 白名单校验；status=1；{plugins, pagination:{page,limit,total,totalPages}} |
| GET | /my | 需登录 | 我的插件 → {plugins} |
| GET | /categories | 公开 | {categories: [tool,...]} |
| GET | /:id | 公开 | 404「Plugin not found」；含 isFavorited（若带 JWT） |
| POST | /upload | 需登录 | multipart file(zip,10MB)；解压找 manifest.json 并校验（规则复制 plugin-validator.js）；同名同作者 → 更新 200，否则新建 201 |
| POST | /:id/download | 公开 | 404；download_count++；文件流下载（filename=name-vversion.zip） |
| POST | /:id/favorite | 需登录 | toggle 收藏；{favorited, favorite_count} |
| DELETE | /:id | 需登录 | 404；非作者 403「You can only delete your own plugins」；删文件+收藏+行 |
| GET | /favorites/list | 需登录 | 我收藏的插件 → {favorites} |

**插件上传校验**（Java 实现，复刻 utils/plugin-validator.js）：
- 仅 .zip（fileFilter），10MB 上限
- `ZipInputStream` 解压找 manifest.json，JSON 解析
- 必填字段、分类合法性（VALID_CATEGORIES）、文件引用存在性校验 → 失败 400 带 details/warnings

测试：`PluginApiIntegrationTest`（列表排序分页/详情 isFavorited/上传校验失败 400/下载计数/收藏 toggle/删除越权 403）。前端 plugins 面板已指向 3000，Java 接管 /api/plugins 后自动可用。删 Express `routes/plugins.js`。

## 六、技术风险与对策

| 风险 | 对策 |
|---|---|
| netty-socketio `getAuthToken()` 取 token null | 批次 A 第一步验证；调整前端 auth 载荷或改用 query 传 token |
| 消息幂等（client_msg_id 唯一键） | `DuplicateKeyException` catch → 409（沿用 like/favorite 并发防护模式） |
| 插件 zip 解压 + manifest 校验 | `ZipInputStream`；校验规则逐条复刻 plugin-validator.js |
| pet:interact 冷却/串门逻辑 | 移植为 `PetInteractionService`，msg_type=5 消息查询 |
| socket 事件与 chat REST 联调 | 同一批次交付，事件测试覆盖推送链路 |

## 七、前端指向汇总（每批次）

| 模块 | 现状（3100=Express） | 迁移后（3000=Java） |
|---|---|---|
| chat REST（ipc-handlers） | EXPRESS_PORT 显式传 3100 | 去掉参数，默认 3000 |
| pet REST（ipc-handlers + pet-state-sync） | EXPRESS_PORT 显式传 3100 | 去掉参数，默认 3000 |
| plugins 面板 | 硬编码 3000（已指向 Java） | 无需改 |
| socket（pet-socket.js） | 硬编码 3100 | 改回 3000 |

## 八、收尾验收

1. chat/pet/plugins/socket 全部走 Java(3000)；Express 仅剩 admin + 静态资源
2. 全量集成测试通过（批次 A/B/C 新增测试 + 既有 52 个无回归）
3. 每批次移除对应 Express 模块后前端调用无 404/500
4. 注释率 ≥20%，中文注释；测试与被测文件同包（controller/）
