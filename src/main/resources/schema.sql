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
