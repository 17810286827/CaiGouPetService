# CaigoPet 前端接口说明（迁移完成版）

> 适用范围：桌宠前端 `CaiGouPet`（Electron）→ 后端 `CaiGouPetService`（Spring Boot）
> 更新日期：2026-08-13
> 背景：Express 后端 `server/` 已全部下线（admin 下线 + 目录删除），前端所有接口统一走 Java 后端。

---

## 一、架构总览

```
桌宠前端 CaiGouPet (Electron gif-viewer)
   │  HTTP REST ──►  Java 后端 3000  (Spring Boot 4 + MyBatis + MySQL)
   │  Socket ─────►  Java 后端 3001  (netty-socketio, 需 socket.enabled=true)
   └── 静态资源 ──►  /api/files/** 与 /uploads/**（Java 上传目录）
```

| 端口 | 服务 | 说明 |
|---|---|---|
| 3000 | REST API + Actuator | 全部业务接口 + 监控端点 |
| 3001 | Socket | chat 实时事件 + pet:interact，**需 `socket.enabled=true` 才监听** |

## 二、通用约定

- **Base URL**：`http://localhost:3000/api`（前端 `server-api.js` 用 `CAIGOPET_API_HOST/PORT` 可覆盖）
- **请求体**：JSON，`Content-Type: application/json`（上传接口用 `multipart/form-data`，字段名 `file`）
- **鉴权**：`Authorization: Bearer <token>`（JWT，登录/注册返回 token）
- **错误响应**：统一 `{ "error": "错误文案" }`，HTTP 状态码语义化（400 参数、401 未认证、403 越权、404 不存在、409 冲突/幂等重复）
- **CORS**：`/api/**` 全放开（复刻 Express `cors({origin:'*'})`）
- **上传大小**：单文件 ≤10MB（multipart 限制）

## 三、认证体系

- **JWT 拦截器**：对 `/api/**` 生效，白名单放行公开认证接口；其余接口需 `Authorization` 头带有效 token，拦截器把当前用户 ID 写入 request attribute
- **@PublicEndpoint 放行**的公开只读接口：拦截器不写当前用户 ID，需要可选用户的接口（如插件详情 `isFavorited`）手动解析 Authorization 头
- **白名单（无需登录）**：`/api/auth/register`、`/login`、`/forgot-password`、`/reset-password`、`/qrcode/init`、`/qrcode/poll`、`/qrcode/confirm`

> 标注说明：下文「公开」= 无需登录可访问；「需登录」= 需 `Bearer token`。

---

## 四、REST 接口清单

### 1. 认证 `/api/auth`

| 方法 | 路径 | 用途 | 鉴权 |
|---|---|---|---|
| POST | `/api/auth/register` | 注册（成功 201 + token，注册即登录） | 公开 |
| POST | `/api/auth/login` | 登录（返回 token + user） | 公开 |
| GET | `/api/auth/me` | 当前登录用户信息 | 需登录 |
| POST | `/api/auth/change-password` | 修改密码 | 需登录 |
| POST | `/api/auth/forgot-password` | 找回密码（模拟流程，重置链接写日志） | 公开 |
| POST | `/api/auth/reset-password` | 重置密码 | 公开 |
| GET/POST | `/api/auth/qrcode/init` | 扫码登录初始化（GET/POST 均支持） | 公开 |
| GET | `/api/auth/qrcode/poll` | 扫码状态轮询 | 公开 |
| POST | `/api/auth/qrcode/confirm` | 手机端确认扫码登录 | 公开 |

### 2. 用户 `/api/users`

| 方法 | 路径 | 用途 | 鉴权 |
|---|---|---|---|
| GET | `/api/users/search?q=` | 搜索用户（匹配用户名/昵称，排除自己） | 需登录 |
| GET | `/api/users/{id}` | 用户详情（404=不存在） | 公开 |
| GET | `/api/users/{id}/posts` | 用户公开帖子列表（分页、创建时间倒序） | 公开 |
| PUT | `/api/users/profile` | 更新资料（仅更新传入非空字段） | 需登录 |

### 3. 帖子 `/api/posts`

| 方法 | 路径 | 用途 | 鉴权 |
|---|---|---|---|
| POST | `/api/posts` | 创建帖子 | 需登录 |
| GET | `/api/posts` | 帖子列表（分页） | 公开 |
| GET | `/api/posts/{id}` | 帖子详情 | 公开 |
| PUT | `/api/posts/{id}` | 编辑帖子 | 需登录 |
| DELETE | `/api/posts/{id}` | 删除帖子 | 需登录 |

### 4. 评论 `/api/comments`

| 方法 | 路径 | 用途 | 鉴权 |
|---|---|---|---|
| POST | `/api/comments` | 发表评论（一级/二级，成功 201） | 需登录 |
| GET | `/api/comments/post/{postId}` | 帖子评论树（根评论倒序+子评论正序，page/limit 默认 1/20） | 公开 |
| DELETE | `/api/comments/{id}` | 删除评论（作者，软删除，越权 403） | 需登录 |

### 5. 点赞 `/api/likes`

| 方法 | 路径 | 用途 | 鉴权 |
|---|---|---|---|
| POST | `/api/likes/{postId}` | 点赞（幂等） | 需登录 |
| DELETE | `/api/likes/{postId}` | 取消点赞 | 需登录 |
| GET | `/api/likes/user/{userId}` | 用户点赞列表 | 公开 |

### 6. 收藏 `/api/favorites`

| 方法 | 路径 | 用途 | 鉴权 |
|---|---|---|---|
| POST | `/api/favorites/{postId}` | 收藏（幂等） | 需登录 |
| DELETE | `/api/favorites/{postId}` | 取消收藏 | 需登录 |
| GET | `/api/favorites/user/{userId}` | 用户收藏列表 | 公开 |

### 7. 关注 `/api/follow`

| 方法 | 路径 | 用途 | 鉴权 |
|---|---|---|---|
| POST | `/api/follow/{userId}` | 关注（幂等） | 需登录 |
| DELETE | `/api/follow/{userId}` | 取消关注 | 需登录 |
| GET | `/api/follow/{userId}/followers` | 粉丝列表 | 公开 |
| GET | `/api/follow/{userId}/following` | 关注列表 | 公开 |

### 8. 资源/文件 `/api/resources`

| 方法 | 路径 | 用途 | 鉴权 |
|---|---|---|---|
| POST | `/api/resources/upload` | 上传文件（成功 201 + `{resource}`，字段 `file`） | 需登录 |
| GET | `/api/resources` | 资源列表 | 公开 |
| GET | `/api/resources/{id}` | 资源详情 | 公开 |
| DELETE | `/api/resources/{id}` | 删除资源 | 需登录 |

> 静态访问：上传文件经 `/api/files/**` 与 `/uploads/**` 提供（映射到 Java 上传目录）。

### 9. 聊天 `/api/chat`

| 方法 | 路径 | 用途 | 鉴权 |
|---|---|---|---|
| POST | `/api/chat/rooms` | 创建聊天室（私聊双向幂等复用：新建 201 / 复用 200，返回 `{room}`） | 需登录 |
| GET | `/api/chat/rooms` | 我的房间列表（含每房间最后一条消息，更新时间倒序） | 需登录 |
| POST | `/api/chat/messages` | 发送消息（成功 201 + `{message}`，全房间 socket 推送；重复 `client_msg_id` 409） | 需登录 |
| GET | `/api/chat/rooms/{roomId}/messages?before=&limit=` | 历史消息（游标分页，升序，缺省最新 limit=50 条，成功后更新已读游标） | 需登录 |
| GET | `/api/chat/rooms/{roomId}` | 房间详情（`{room, members}`，成员为用户信息） | 需登录 |

### 10. 宠物 `/api/pet`

| 方法 | 路径 | 用途 | 鉴权 |
|---|---|---|---|
| GET | `/api/pet` | 获取宠物状态（无记录则创建默认，emotion_state/personality 为 `{}`） | 需登录 |
| PUT | `/api/pet/sync` | 同步宠物状态（emotion_state/personality 原样 JSON 存取） | 需登录 |
| GET | `/api/pet/visit-settings?room_id=` | 串门设置（带 room_id 校验成员并返回对方允许状态 `other_allow`） | 需登录 |
| PUT | `/api/pet/visit-settings` | 全局串门开关（allow 必须为 boolean，否则 400） | 需登录 |
| PUT | `/api/pet/visit-settings/room/{roomId}` | 房间级串门覆盖（allow=boolean upsert / null 删除覆盖） | 需登录 |

### 11. 插件 `/api/plugins`

| 方法 | 路径 | 用途 | 鉴权 |
|---|---|---|---|
| GET | `/api/plugins` | 插件列表（分页/排序/过滤） | 公开 |
| GET | `/api/plugins/categories` | 插件分类 | 公开 |
| GET | `/api/plugins/my` | 我的插件 | 需登录 |
| GET | `/api/plugins/favorites/list` | 我的收藏列表（`{favorites:[内嵌大写 Plugin]}`，前端读 `f.Plugin`） | 需登录 |
| POST | `/api/plugins/upload` | 上传插件（zip + manifest 校验；200=同名更新 / 201=新建） | 需登录 |
| POST | `/api/plugins/{id}/download` | 下载插件（下载数自增后返回 `{name}-v{version}.zip`） | 公开 |
| POST | `/api/plugins/{id}/favorite` | 收藏/取消收藏 toggle | 需登录 |
| DELETE | `/api/plugins/{id}` | 删除插件 | 需登录 |
| GET | `/api/plugins/{id}` | 插件详情（带 Authorization 头时计算 isFavorited） | 公开 |

---

## 五、Socket 接口（端口 3001，netty-socketio）

> **启动前提**：Java 必须带 `socket.enabled=true`（IDEA VM options 或 `env` 前缀），否则 3001 不监听。
> **连接**：`http://localhost:3001`，**鉴权走 URL query `?token=`**（netty-socketio 2.0.13 握手阶段 `getAuthToken()` 恒 null，socket.io v4 auth 载荷不可用）。
> 前端：`pet-socket.js` 连 3001 + `query:{token}`，可用 `CAIGOPET_SOCKET_URL` 覆盖。

| 事件 | 方向 | 说明 |
|---|---|---|
| `chat:join` | 客户端→服务端 | 加入房间（参数 roomId；广播 `chat:user_joined`） |
| `chat:leave` | 客户端→服务端 | 离开房间 |
| `chat:typing` | 双向 | 正在输入（房间广播） |
| `chat:stop_typing` | 双向 | 停止输入（房间广播） |
| `chat:read` | 双向 | 已读游标（房间广播） |
| `chat:message` | 双向 | 聊天消息（服务端落库后广播） |
| `chat:user_joined` | 服务端→客户端 | 用户加入房间通知 |
| `pet:interact` | 客户端→服务端 | 宠物互动（8 动作/串门/30s 冷却，房间内广播） |
| `pet:interact_ack` | 服务端→客户端 | 互动成功回执（含 cooldown_until） |
| `pet:interact_reject` | 服务端→客户端 | 互动拒绝（`PERMISSION_DENIED` / `COOLDOWN`，含 retry_after_ms） |

## 六、Actuator 监控（端口 3000）

> 替代已下线的 Express admin 面板，暴露 health/info/metrics 三个非敏感端点（不暴露 env/beans/threaddump/heapdump）。

| 端点 | 说明 |
|---|---|
| `/actuator/health` | 健康检查（存活 + MySQL 连通），正常返回 `{"status":"UP"}` |
| `/actuator/info` | 应用信息 |
| `/actuator/metrics` | JVM 指标（内存/HTTP 请求等），预留接 Prometheus/Grafana |

---

## 七、历史说明

- Express 后端 `CaiGouPet/server/` 已于 2026-08-13 全部下线并删除（admin 管理后台下线、admin.html 废弃、socket.io 移除）
- 前端启动脚本 `start.bat/ps1` 现只等待 Java 3000 就绪（`/actuator/health`）后拉起桌宠
- 若需健康检查桌宠冷启动，等 Java 的 `/actuator/health` 返回 UP 即可

## 八、关键文件索引

| 文件 | 作用 |
|---|---|
| `CaiGouPet/gif-viewer/src/server-api.js` | 前端 REST 封装（默认 3000） |
| `CaiGouPet/gif-viewer/src/pet-socket.js` | 前端 Socket 封装（3001 + query token） |
| `CaiGouPetService/src/main/java/caigou/caigoupetservice/controller/*.java` | 后端接口实现（11 个模块） |
| `CaiGouPetService/src/main/java/caigou/caigoupetservice/socket/SocketConfig.java` | Socket 事件实现 |
| `CaiGouPetService/src/main/java/caigou/caigoupetservice/config/WebConfig.java` | 拦截器/CORS/静态资源 |
