# 接口迁移 P1：用户与社交关系模块 设计文档

> 文档版本：v1.0（2026-08-11）
> 头脑风暴产出，经用户评审批准。
> 目标：将前端仓 `CaiGouPet/server`（Node.js Express）的 API 逻辑全量迁移到 Java 仓 `CaiGouPetService`，后端统一处理接口。

---

## 一、背景与目标

前端项目 `CaiGouPet`（Electron 桌面宠物 + 社交社区）的后端接口目前都在该仓的 `server/`（Express + Sequelize + SQLite）中。规划逐步迁移到独立后端仓 `CaiGouPetService`（Spring Boot + MyBatis + MySQL）。

**本次目标**：全量迁移 Express 能力到 Java，逐模块对齐行为，最终清空前端仓 `server/` 目录。

**已确认的全局决策**：

| 决策点 | 结论 |
|---|---|
| 迁移范围 | 全量迁移 Express 能力（12 组 REST 路由 + socket 实时 + 文件上传 + admin），1:1 行为对齐 |
| 实时通信 | netty-socketio（Java 端实现 socket.io 协议，前端 socket.io-client@4.8.3 零改动）；**先做 Engine.IO v4 兼容 POC**，不通过则降级 |
| 迁移顺序 | P1 用户与社交关系 → P2 资源+帖子+空间模块 → P3 聊天+socket → P4 宠物 → P5 插件/admin |
| 空间模块 | 并入 P2 一起做（posts/social 卡片流/书签） |
| 认证策略 | **注解方案**：新增 `@PublicEndpoint` 注解标记公开接口，拦截器按注解放行 |
| 编码规范 | 注释率 ≥20% 中文注释；entity/dto 用 Lombok（禁 record）；建表字段全中文 COMMENT |
| **测试文件位置** | **与被测文件同包路径**（`src/test` 镜像 `src/main` 包结构） |

---

## 二、整体迁移路线图

| 阶段 | 模块 | 交付物 |
|---|---|---|
| P1（本文档） | users / follows / likes / favorites（+ posts 表） | 12 端点 + 4 表 + 集成测试，contacts/homepage 面板可用 |
| P2 | posts CRUD / resources 上传 / space 空间模块 | 帖子生态 + 书签，homepage/space 面板真实数据 |
| P3 | chat + socket 实时 | netty-socketio POC + 完整事件，chat 面板可用 |
| P4 | pet | 宠物状态同步 |
| P5 | plugins / admin | 插件市场 + 管理后台 |

每阶段独立交付：Java 实现 → 集成测试对齐 Express 行为 → 前端面板验证 → 移除对应 Express 模块。

---

## 三、P1 范围

### 3.1 端点清单（14 个）

| # | 方法 | 路径 | 认证 | 所属 |
|---|---|---|---|---|
| 1 | GET | `/api/users/search?q=` | 需登录 | users |
| 2 | GET | `/api/users/:id` | 公开 | users |
| 3 | PUT | `/api/users/profile` | 需登录 | users |
| 4 | GET | `/api/users/:id/posts` | 公开 | users |
| 5 | POST | `/api/follow/:userId` | 需登录 | follows |
| 6 | DELETE | `/api/follow/:userId` | 需登录 | follows |
| 7 | GET | `/api/follow/:userId/followers` | 公开 | follows |
| 8 | GET | `/api/follow/:userId/following` | 公开 | follows |
| 9 | POST | `/api/likes/:postId` | 需登录 | likes |
| 10 | DELETE | `/api/likes/:postId` | 需登录 | likes |
| 11 | GET | `/api/likes/user/:userId` | 公开 | likes |
| 12 | POST | `/api/favorites/:postId` | 需登录 | favorites |
| 13 | DELETE | `/api/favorites/:postId` | 需登录 | favorites |
| 14 | GET | `/api/favorites/user/:userId` | 公开 | favorites |

### 3.2 依赖发现

likes/favorites 的增删需校验帖子存在（status=1），`GET /likes/user/:id`、`GET /favorites/user/:id`、`GET /users/:id/posts` 需连 posts 表返回帖子列表。因此 **P1 引入 posts 表**（按 Express Post 模型完整建表，仅做只读关联查询）；帖子 CRUD 路由留到 P2。

---

## 四、数据库设计

在 `schema.sql` 幂等追加 4 张表（每字段中文 COMMENT）：

### follows（关注表）
| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT 自增 | 主键 |
| user_id | BIGINT NOT NULL | 被关注者（外键→users.id） |
| follower_id | BIGINT NOT NULL | 关注者（外键→users.id） |
| created_at | TIMESTAMP | 创建时间 |
| 唯一键 | uk_user_follower (user_id, follower_id) | |

### likes（点赞表）
| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT 自增 | 主键 |
| user_id | BIGINT NOT NULL | 点赞者 |
| post_id | BIGINT NOT NULL | 帖子 |
| created_at | TIMESTAMP | 创建时间 |
| 唯一键 | uk_user_post (user_id, post_id) | |

### favorites（收藏表）
同 likes 结构（uk_user_post）。

### posts（帖子表，P1 仅建表供关联查询）
| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT 自增 | 主键 |
| user_id | BIGINT NOT NULL | 作者（外键→users.id） |
| title | VARCHAR(200) | 标题（可空） |
| content | TEXT | 正文（长文本） |
| content_type | TINYINT DEFAULT 0 | 0=纯文本 1=markdown 2=富文本 |
| summary | VARCHAR(500) | 摘要 |
| cover_url | VARCHAR(500) | 封面图 URL |
| tags | JSON | 标签数组 |
| status | TINYINT DEFAULT 0 | 0=草稿 1=公开 2=删除 |
| view_count | INT DEFAULT 0 | 浏览数 |
| like_count | INT DEFAULT 0 | 点赞数 |
| comment_count | INT DEFAULT 0 | 评论数 |
| is_top | TINYINT DEFAULT 0 | 是否置顶 |
| deleted_at | TIMESTAMP | 软删除时间 |
| created_at / updated_at | TIMESTAMP | 时间戳 |
| 索引 | idx_user_status (user_id, status)、idx_status_created (status, created_at) | |

> 行为契约以查询条件为准：likes/favorites/users-posts 均过滤 `status=1`（已公开）。

---

## 五、接口契约（行为对齐要点）

响应统一 **snake_case**；成功直接返回数据体，失败 `{error:'中文'}` + 状态码；分页 `page`（从 1 起）/`limit`（默认 20）。全部复刻 Express：

| 端点 | 关键行为 |
|---|---|
| `GET /users/search` | q 空→`{users:[]}`；LIKE `%q%` 匹配 username/nickname，排除自己，limit 20 → `{users:[{id,username,nickname,avatar_url,gender}]}` |
| `GET /users/:id` | 404「用户不存在」；返回完整字段（含 following_count 等）→ `{user:{...}}` |
| `PUT /users/profile` | 部分更新（仅传入字段），返回更新后完整用户 |
| `GET /users/:id/posts` | 分页，status=1，created_at DESC → `{posts,total,page}`，帖子内嵌作者 |
| `POST /follow/:userId` | 关注自己→400「不能关注自己」；目标不存在/禁用→404「用户不存在」；findOrCreate——新建 201 且 following_count/followers_count 各 +1，已存在 200 → `{follow,created}` |
| `DELETE /follow/:userId` | 未关注→404「未关注此用户」；删除并计数各 -1 → `{message:'取消关注成功'}` |
| `GET /follow/:userId/followers|following` | 分页，每行 Follow 内嵌 follower/following 用户对象 → `{followers,following,total,page}` |
| `POST /likes/:postId` | 帖子不存在/status≠1→404「帖子不存在」；新建 201 递增 post.like_count + 作者 likes_count，已存在 200 → `{like,created}` |
| `DELETE /likes/:postId` | 未点赞→404「未点赞」；删除并递减 → `{message:'取消点赞成功'}` |
| `GET /likes/user/:userId` | 分页，**展平为 `{posts:[...]}`**，帖子内嵌作者 |
| `POST /favorites/:postId` | 同 likes，但只递增用户 favorites_count → `{favorite,created}` |
| `DELETE /favorites/:postId` | 未收藏→404「未收藏」→ `{message:'取消收藏成功'}` |
| `GET /favorites/user/:userId` | 同 likes/user，`{posts:[...]}` |

---

## 六、Java 分层结构

```
controller/  UserController、FollowController、LikeController、FavoriteController
service/     UserService、FollowService、LikeService、FavoriteService
mapper/      FollowMapper、LikeMapper、FavoriteMapper、PostMapper(只读)
entity/      Follow、Like、Favorite、Post
dto/         ProfileUpdateRequest、UserSearchView 等
annotation/  PublicEndpoint
interceptor/ JwtAuthInterceptor（识别 @PublicEndpoint 放行）
```

沿用现有约定：controller 只做参数校验与返回；service 抛 `ApiException(status, message)`；全局异常处理器统一兜底返回 `{error}`。UserView 现有 record 仅读取复用，**新增 dto 一律 Lombok**。

---

## 七、认证策略（注解方案）

Express 是逐路由挂 `authMiddleware`；Java 现在用「全局拦截器 + 白名单」。为对齐 Express 语义、避免后续阶段白名单膨胀，采用**注解方案**：

- 新增 `@PublicEndpoint`（注解 `@Target(METHOD)` 的标记注解）。
- `JwtAuthInterceptor.preHandle` 中：若 HandlerMethod 上标有 `@PublicEndpoint` → 直接放行；否则走现有 token 校验。
- 现有认证白名单（`WebConfig.excludePathPatterns`）保留，供 login/register 等认证接口使用。
- P1 中 6 个公开 GET 加 `@PublicEndpoint`：`/users/:id`、`/users/:id/posts`、`/follow/:id/followers`、`/follow/:id/following`、`/likes/user/:id`、`/favorites/user/:id`。
- `GET /users/search` **不加**注解（需登录），由拦截器正常校验。

---

## 八、迁移方法与测试

### 8.1 迁移方法

逐模块对照移植：以 Express 代码为行为 oracle，每个端点把校验分支、错误文案、状态码、响应字段逐一对齐。Express 保留作参照，模块验证通过后移除对应文件。

### 8.2 测试（位置与被测文件同包）

- **测试文件位置**：`src/test/java` 镜像 `src/main/java` 包结构，**每个模块的集成测试与被测 Controller 同包**。如 `UserController`（包 `...controller`）的测试放 `src/test/java/caigou/caigoupetservice/controller/UserApiIntegrationTest.java`；Follow/Like/Favorite 同理。
- 延续 `AuthApiIntegrationTest` 模式：连真实 MySQL，`DB_PASS='chen9911.' ./mvnw test` 运行；`@AfterEach` 按测试前缀清理数据。
- 现有 `AuthApiIntegrationTest.java`（平铺在 `caigou.caigoupetservice`）按新约定迁到 `.../controller/` 下。
- P1 新增四个模块集成测试（`controller/` 下）：`UserApiIntegrationTest`、`FollowApiIntegrationTest`、`LikeApiIntegrationTest`、`FavoriteApiIntegrationTest`，覆盖：关注/取关/自己/不存在/重复/计数变化、点赞/取消/未点赞/帖子不存在/计数、收藏同、搜索、资料获取/更新、分页列表、认证边界（无 token 访问需认证接口→401，公开接口无 token→200）。

---

## 九、与现有实现计划的关系

`docs/superpowers/plans/2026-08-11-batch0-poc-batch1-community.md` 已有本迁移的 Task 1-7 实现计划（POC + 基础设施 + resources/users/posts/likes/favorites/follows/comments）。该计划与本次确认的决策有**两处冲突，需在实现计划环节更新**：

1. 认证：计划用「公开 GET 白名单」→ 改为 **`@PublicEndpoint` 注解方案**（本设计第七章）。
2. 测试位置：计划把测试平铺在 `caigou.caigoupetservice` → 改为**与被测文件同包**（本设计第八章）。

---

## 十、验收标准

1. P1 的 14 个端点全部实现，行为与 Express 对齐（对比验证）。
2. contacts/homepage 面板用真实数据正常工作。
3. Express 的 users/follows/likes/favorites 模块移除后，前端调用无 404。
4. 注释率 ≥20%，关键方法有中文注释；新增表字段全部带中文 COMMENT。
5. 测试文件与被测文件同包路径，全部通过。
