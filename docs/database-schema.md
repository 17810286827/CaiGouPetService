# CaigoPet 数据库表结构说明

> 适用后端：`CaiGouPetService`（Spring Boot + MyBatis + MySQL）
> 更新日期：2026-08-13
> 权威 DDL：`src/main/resources/schema.sql`（服务启动时自动执行，`IF NOT EXISTS` 幂等）

---

## 一、总体约定

| 项 | 约定 |
|---|---|
| 数据库 | `caigoupet`（MySQL） |
| 引擎 | 全部 `InnoDB` |
| 字符集 | `utf8mb4 / utf8mb4_unicode_ci` |
| 主键 | 全部 `id BIGINT AUTO_INCREMENT` |
| 时间 | `created_at` 默认当前时间；`updated_at` 随更新自动刷新（`ON UPDATE CURRENT_TIMESTAMP`） |
| 软删除 | 普遍用 `status` 字段标记（各表语义见字段说明），另有 `deleted_at` 表用删除时间 |
| 特殊存储 | `reset_token_expires` 用 BIGINT 存 epoch 毫秒；`pet_states` 用 JSON 列存情绪/性格对象 |

## 二、表清单（14 张）

| 模块 | 表 | 用途 |
|---|---|---|
| 用户 | `users` | 用户账号与资料 |
| 社区 | `posts` | 帖子 |
| 社区 | `comments` | 评论（支持两级：parent_id 挂回复，root_id 聚合） |
| 社区 | `likes` | 点赞关系 |
| 社区 | `favorites` | 收藏关系 |
| 社区 | `follows` | 关注关系 |
| 社区 | `resources` | 上传文件资源（图片/视频/文件/音频） |
| 聊天 | `chat_rooms` | 聊天室（私聊/群聊） |
| 聊天 | `chat_room_members` | 聊天室成员与已读游标 |
| 聊天 | `messages` | 聊天消息（含客户端幂等 ID） |
| 宠物 | `pet_states` | 宠物状态（情绪/性格，每用户一条） |
| 宠物 | `pet_visit_settings` | 宠物串门设置（全局/房间级） |
| 插件 | `plugins` | 插件信息与文件 |
| 插件 | `plugin_favorites` | 插件收藏关系 |

---

## 三、用户模块

### users 用户表

**DDL：**

```sql
CREATE TABLE IF NOT EXISTS users (
    id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    username            VARCHAR(50)  NOT NULL COMMENT '用户名(唯一)',
    password            VARCHAR(255) NOT NULL COMMENT '密码(bcrypt哈希,不存明文)',
    nickname            VARCHAR(100) NULL COMMENT '昵称',
    avatar_url          VARCHAR(500) NULL COMMENT '头像地址',
    email               VARCHAR(100) NULL COMMENT '邮箱',
    phone               VARCHAR(20)  NULL COMMENT '手机号',
    gender              VARCHAR(10)  NULL COMMENT '性别(male/female/other)',
    bio                 VARCHAR(500) NULL COMMENT '个人简介',
    ip                  VARCHAR(45)  NULL COMMENT '注册IP',
    province            VARCHAR(50)  NULL COMMENT '省份',
    city                VARCHAR(50)  NULL COMMENT '城市',
    following_count     INT          NOT NULL DEFAULT 0 COMMENT '关注数',
    followers_count     INT          NOT NULL DEFAULT 0 COMMENT '粉丝数',
    likes_count         INT          NOT NULL DEFAULT 0 COMMENT '获赞数',
    favorites_count     INT          NOT NULL DEFAULT 0 COMMENT '收藏数',
    reset_token         VARCHAR(255) NULL COMMENT '找回密码令牌(sha256十六进制)',
    reset_token_expires BIGINT       NULL COMMENT '找回密码令牌过期时间(epoch毫秒)',
    status              TINYINT      NOT NULL DEFAULT 1 COMMENT '状态:1=正常 0=禁用',
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username),
    KEY idx_email (email),
    KEY idx_reset_token (reset_token)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';
```

**字段说明：**

| 字段 | 类型 | 键/约束 | 默认 | 说明 |
|---|---|---|---|---|
| id | BIGINT | PK, 自增 | - | 主键 ID |
| username | VARCHAR(50) | 唯一, 非空 | - | 用户名 |
| password | VARCHAR(255) | 非空 | - | 密码（bcrypt 哈希，不存明文） |
| nickname | VARCHAR(100) | 可空 | - | 昵称 |
| avatar_url | VARCHAR(500) | 可空 | - | 头像地址 |
| email | VARCHAR(100) | 可空 | - | 邮箱（有索引） |
| phone | VARCHAR(20) | 可空 | - | 手机号 |
| gender | VARCHAR(10) | 可空 | - | 性别（male/female/other） |
| bio | VARCHAR(500) | 可空 | - | 个人简介 |
| ip | VARCHAR(45) | 可空 | - | 注册 IP |
| province / city | VARCHAR(50) | 可空 | - | 省份 / 城市 |
| following_count | INT | 非空 | 0 | 关注数 |
| followers_count | INT | 非空 | 0 | 粉丝数 |
| likes_count | INT | 非空 | 0 | 获赞数 |
| favorites_count | INT | 非空 | 0 | 收藏数 |
| reset_token | VARCHAR(255) | 可空 | - | 找回密码令牌（sha256 十六进制，有索引） |
| reset_token_expires | BIGINT | 可空 | - | 令牌过期时间（epoch 毫秒） |
| status | TINYINT | 非空 | 1 | 状态：1=正常 0=禁用 |
| created_at | TIMESTAMP | 非空 | 当前时间 | 创建时间 |
| updated_at | TIMESTAMP | 非空 | 自动更新 | 更新时间 |

**索引：** 主键 `id`；唯一 `uk_username(username)`；普通 `idx_email(email)`、`idx_reset_token(reset_token)`

---

## 四、社区模块

### posts 帖子表

**DDL：**

```sql
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
```

**字段说明：**

| 字段 | 类型 | 键/约束 | 默认 | 说明 |
|---|---|---|---|---|
| id | BIGINT | PK, 自增 | - | 主键 ID |
| user_id | BIGINT | 非空 | - | 作者 ID（外键→users.id） |
| title | VARCHAR(200) | 可空 | - | 标题 |
| content | TEXT | 可空 | - | 正文（长文本） |
| content_type | TINYINT | 非空 | 0 | 0=纯文本 1=markdown 2=富文本 |
| summary | VARCHAR(500) | 可空 | - | 摘要 |
| cover_url | VARCHAR(500) | 可空 | - | 封面图 URL |
| tags | JSON | 可空 | - | 标签数组 |
| status | TINYINT | 非空 | 0 | 0=草稿 1=公开 2=删除 |
| view_count / like_count / comment_count | INT | 非空 | 0 | 浏览 / 点赞 / 评论数 |
| is_top | TINYINT | 非空 | 0 | 是否置顶 |
| deleted_at | TIMESTAMP | 可空 | - | 软删除时间 |
| created_at / updated_at | TIMESTAMP | 非空 | - | 创建 / 更新时间 |

**索引：** 主键 `id`；`idx_user_status(user_id, status)`、`idx_status_created(status, created_at)`

### comments 评论表

```sql
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
```

| 字段 | 类型 | 键/约束 | 默认 | 说明 |
|---|---|---|---|---|
| id | BIGINT | PK, 自增 | - | 主键 ID |
| post_id | BIGINT | 非空 | - | 所属帖子 ID |
| user_id | BIGINT | 非空 | - | 评论者 ID |
| parent_id | BIGINT | 可空 | - | 父评论 ID（一级评论为空，二级挂回复） |
| root_id | BIGINT | 可空 | - | 根评论 ID（聚合同串回复） |
| content | TEXT | 非空 | - | 评论内容 |
| like_count | INT | 非空 | 0 | 点赞数 |
| status | TINYINT | 非空 | 1 | 1=正常 0=删除（软删除） |
| created_at / updated_at | TIMESTAMP | 非空 | - | 创建 / 更新时间 |

### likes 点赞表

```sql
CREATE TABLE IF NOT EXISTS likes (
    id         BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    user_id    BIGINT   NOT NULL COMMENT '点赞者ID',
    post_id    BIGINT   NOT NULL COMMENT '帖子ID',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_post (user_id, post_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='点赞表';
```

| 字段 | 类型 | 键/约束 | 默认 | 说明 |
|---|---|---|---|---|
| id | BIGINT | PK, 自增 | - | 主键 ID |
| user_id | BIGINT | 非空 | - | 点赞者 ID |
| post_id | BIGINT | 非空 | - | 帖子 ID |
| created_at | TIMESTAMP | 非空 | 当前时间 | 创建时间 |

**索引：** 唯一 `uk_user_post(user_id, post_id)`（同一用户同一帖只能点赞一次）

### favorites 收藏表

```sql
CREATE TABLE IF NOT EXISTS favorites (
    id         BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    user_id    BIGINT   NOT NULL COMMENT '收藏者ID',
    post_id    BIGINT   NOT NULL COMMENT '帖子ID',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_post (user_id, post_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='收藏表';
```

| 字段 | 类型 | 键/约束 | 默认 | 说明 |
|---|---|---|---|---|
| id | BIGINT | PK, 自增 | - | 主键 ID |
| user_id | BIGINT | 非空 | - | 收藏者 ID |
| post_id | BIGINT | 非空 | - | 帖子 ID |
| created_at | TIMESTAMP | 非空 | 当前时间 | 创建时间 |

**索引：** 唯一 `uk_user_post(user_id, post_id)`

### follows 关注表

```sql
CREATE TABLE IF NOT EXISTS follows (
    id          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    user_id     BIGINT   NOT NULL COMMENT '被关注者ID',
    follower_id BIGINT   NOT NULL COMMENT '关注者ID',
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_follower (user_id, follower_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='关注表';
```

| 字段 | 类型 | 键/约束 | 默认 | 说明 |
|---|---|---|---|---|
| id | BIGINT | PK, 自增 | - | 主键 ID |
| user_id | BIGINT | 非空 | - | 被关注者 ID |
| follower_id | BIGINT | 非空 | - | 关注者 ID |
| created_at | TIMESTAMP | 非空 | 当前时间 | 创建时间 |

**索引：** 唯一 `uk_user_follower(user_id, follower_id)`

### resources 资源表

```sql
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
```

| 字段 | 类型 | 键/约束 | 默认 | 说明 |
|---|---|---|---|---|
| id | BIGINT | PK, 自增 | - | 主键 ID |
| user_id | BIGINT | 非空 | - | 上传者 ID |
| type | TINYINT | 非空 | - | 1=图片 2=视频 3=文件 4=音频 |
| original_name | VARCHAR(255) | 非空 | - | 原始文件名 |
| storage_path | VARCHAR(500) | 非空 | - | 磁盘存储文件名（uuid） |
| url | VARCHAR(500) | 非空 | - | 访问 URL（/api/files/文件名） |
| thumbnail_url | VARCHAR(500) | 可空 | - | 缩略图 URL |
| size | BIGINT | 非空 | 0 | 文件字节数 |
| mime_type | VARCHAR(50) | 可空 | - | MIME 类型 |
| width / height | INT | 可空 | - | 图片宽 / 高 |
| duration | INT | 可空 | - | 音视频时长（秒） |
| md5 | VARCHAR(32) | 可空 | - | 文件 MD5（去重，有索引） |
| status | TINYINT | 非空 | 1 | 1=正常 0=删除 |
| created_at / updated_at | TIMESTAMP | 非空 | - | 创建 / 更新时间 |

---

## 五、聊天模块

### chat_rooms 聊天室表

```sql
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
```

| 字段 | 类型 | 键/约束 | 默认 | 说明 |
|---|---|---|---|---|
| id | BIGINT | PK, 自增 | - | 主键 ID |
| type | TINYINT | 非空 | - | 1=私聊 2=群聊 |
| name | VARCHAR(100) | 可空 | - | 聊天室名称（群聊用） |
| avatar_url | VARCHAR(500) | 可空 | - | 头像 URL |
| created_by | BIGINT | 非空 | - | 创建者 ID |
| created_at / updated_at | TIMESTAMP | 非空 | - | 创建 / 更新时间 |

> 私聊幂等：按成员组合查重复用同一条 `chat_rooms` 记录。

### chat_room_members 聊天室成员表

```sql
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
```

| 字段 | 类型 | 键/约束 | 默认 | 说明 |
|---|---|---|---|---|
| id | BIGINT | PK, 自增 | - | 主键 ID |
| room_id | BIGINT | 非空 | - | 聊天室 ID |
| user_id | BIGINT | 非空 | - | 成员 ID |
| role | TINYINT | 非空 | 0 | 2=创建者 0=成员 |
| last_read_msg_id | BIGINT | 非空 | 0 | 最后已读消息 ID（已读游标） |
| created_at | TIMESTAMP | 非空 | 当前时间 | 创建时间 |

**索引：** 唯一 `uk_room_user(room_id, user_id)`

### messages 消息表

```sql
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
```

| 字段 | 类型 | 键/约束 | 默认 | 说明 |
|---|---|---|---|---|
| id | BIGINT | PK, 自增 | - | 主键 ID |
| room_id | BIGINT | 非空 | - | 聊天室 ID |
| sender_id | BIGINT | 非空 | - | 发送者 ID |
| msg_type | TINYINT | 非空 | 0 | 0=文本 1=图 2=视频 3=文件 4=音频 5=系统 |
| content | TEXT | 可空 | - | 消息内容（msg_type=5 为 JSON 字符串，如 pet_interact） |
| resource_id | BIGINT | 可空 | - | 资源 ID |
| reply_to | BIGINT | 可空 | - | 回复的消息 ID |
| status | TINYINT | 非空 | 1 | 1=正常 0=撤回 -1=删除 |
| client_msg_id | VARCHAR(64) | 可空 | - | 客户端消息 ID（幂等去重，重复发 409） |
| created_at | TIMESTAMP | 非空 | 当前时间 | 创建时间 |

**索引：** 唯一 `uk_sender_client(sender_id, client_msg_id)`；`idx_room_created(room_id, created_at)`（历史游标分页）

---

## 六、宠物模块

### pet_states 宠物状态表

```sql
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
```

| 字段 | 类型 | 键/约束 | 默认 | 说明 |
|---|---|---|---|---|
| id | BIGINT | PK, 自增 | - | 主键 ID |
| user_id | BIGINT | 非空 | - | 用户 ID（唯一，每用户一条） |
| emotion_state | JSON | 可空 | - | 情绪状态对象 |
| personality | JSON | 可空 | - | 性格对象 |
| last_sync_at | TIMESTAMP | 可空 | - | 最后同步时间 |
| created_at / updated_at | TIMESTAMP | 非空 | - | 创建 / 更新时间 |

**索引：** 唯一 `uk_user(user_id)`

### pet_visit_settings 宠物串门设置表

```sql
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
```

| 字段 | 类型 | 键/约束 | 默认 | 说明 |
|---|---|---|---|---|
| id | BIGINT | PK, 自增 | - | 主键 ID |
| user_id | BIGINT | 非空 | - | 用户 ID |
| room_id | BIGINT | 可空 | - | 聊天室 ID（空 = 全局设置） |
| allow | TINYINT | 非空 | 1 | 是否允许串门：1=允许 0=拒绝 |
| created_at / updated_at | TIMESTAMP | 非空 | - | 创建 / 更新时间 |

**索引：** 唯一 `uk_user_room(user_id, room_id)`

> 已知注意：全局行（room_id=NULL）的 MySQL 唯一索引对 NULL 不判重，并发首访可能产生两条全局行（概率极低，backlog 项，根治需生成列唯一索引）。

---

## 七、插件模块

### plugins 插件表

```sql
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
```

| 字段 | 类型 | 键/约束 | 默认 | 说明 |
|---|---|---|---|---|
| id | BIGINT | PK, 自增 | - | 主键 ID |
| name | VARCHAR(100) | 非空 | - | 插件名称 |
| version | VARCHAR(20) | 非空 | 1.0.0 | 版本号 |
| description | TEXT | 可空 | - | 插件描述 |
| author_id | BIGINT | 非空 | - | 作者 ID |
| category | VARCHAR(50) | 非空 | tool | 分类 |
| tags | VARCHAR(500) | 可空 | - | 标签（逗号分隔） |
| icon | VARCHAR(500) | 可空 | - | 图标 URL |
| download_count | INT | 非空 | 0 | 下载数 |
| favorite_count | INT | 非空 | 0 | 收藏数 |
| manifest_json | TEXT | 可空 | - | 插件清单 JSON |
| file_path | VARCHAR(500) | 可空 | - | 插件文件路径 |
| file_size | INT | 非空 | 0 | 文件字节数 |
| status | TINYINT | 非空 | 1 | 0=待审 1=通过 2=拒绝 |
| review_comment | VARCHAR(500) | 可空 | - | 审核意见 |
| created_at / updated_at | TIMESTAMP | 非空 | - | 创建 / 更新时间 |

**索引：** 主键 `id`；`idx_category(category)`、`idx_status(status)`

### plugin_favorites 插件收藏表

```sql
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

| 字段 | 类型 | 键/约束 | 默认 | 说明 |
|---|---|---|---|---|
| id | BIGINT | PK, 自增 | - | 主键 ID |
| user_id | BIGINT | 非空 | - | 用户 ID |
| plugin_id | BIGINT | 非空 | - | 插件 ID |
| created_at | TIMESTAMP | 非空 | 当前时间 | 创建时间 |

**索引：** 唯一 `uk_user_plugin(user_id, plugin_id)`；`idx_plugin(plugin_id)`

---

## 八、表间关系摘要

```
users 1 ── * posts                (posts.user_id)
users 1 ── * comments             (comments.user_id)
posts 1 ── * comments             (comments.post_id)
comments 1 ── * comments          (自关联: parent_id 挂回复, root_id 聚合)
users 1 ── * likes / favorites / follows / resources / plugins
users 1 ── 1 pet_states           (唯一 user_id)
chat_rooms 1 ── * chat_room_members / messages
users 1 ── * plugin_favorites     (plugins 收藏)
```

> 说明：外键均为逻辑外键（DDL 未建物理 FOREIGN KEY 约束），由 service 层保证引用完整性；`users.likes_count/favorites_count/followers_count` 等计数冗余字段由业务维护。
