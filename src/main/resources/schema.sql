-- CaiGouPetService 初始化建表脚本(MySQL 方言,目标库:caigoupet;幂等 IF NOT EXISTS,服务启动时自动执行)
-- 前置条件:MySQL 中需存在 caigoupet 库(连接串已指定,库不存在则连接失败)
-- 时间存储约定:reset_token_expires 用 BIGINT 存 epoch 毫秒(与代码 System.currentTimeMillis() 一致)
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

-- 若 caigoupet 库尚不存在,先手动执行一次(或用任意 MySQL 客户端连 root 执行):
-- CREATE DATABASE IF NOT EXISTS caigoupet DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ===== 社区模块 =====
CREATE TABLE IF NOT EXISTS posts (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    user_id       BIGINT       NOT NULL COMMENT '作者ID(外键→users.id)',
    title         VARCHAR(200) NULL COMMENT '标题(可空)',
    content       TEXT         NULL COMMENT '正文(长文本)',
    content_type  TINYINT      NOT NULL DEFAULT 0 COMMENT '内容类型:0=纯文本 1=markdown 2=富文本',
    summary       VARCHAR(500) NULL COMMENT '摘要(可空)',
    cover_url     TEXT         NULL COMMENT '封面图URL(逗号分隔多图)',
    tags          JSON         NULL COMMENT '标签数组',
    status        TINYINT      NOT NULL DEFAULT 0 COMMENT '状态:0=草稿 1=公开 2=删除',
    visibility    TINYINT      NOT NULL DEFAULT 1 COMMENT '可见性:1公开 2仅粉丝 3仅好友 4仅自己',
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

-- posts 幂等迁移(老库升级用;continue-on-error=true 容忍重复执行)
ALTER TABLE posts ADD COLUMN visibility TINYINT NOT NULL DEFAULT 1 COMMENT '可见性:1公开 2仅粉丝 3仅好友 4仅自己';
ALTER TABLE posts MODIFY cover_url TEXT NULL COMMENT '封面图URL(逗号分隔多图)';

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
