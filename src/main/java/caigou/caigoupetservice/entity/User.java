package caigou.caigoupetservice.entity;

import lombok.Data;

/**
 * 用户实体,对应 users 表
 * created_at/updated_at 由数据库维护,不参与 insert
 * getter/setter 由 Lombok @Data 编译期生成
 */
@Data
public class User {

    /** 主键(自增) */
    private Long id;

    /** 用户名(唯一) */
    private String username;

    /** 密码(bcrypt hash) */
    private String password;

    /** 昵称 */
    private String nickname;

    /** 头像地址 */
    private String avatarUrl;

    /** 邮箱 */
    private String email;

    /** 手机号 */
    private String phone;

    /** 性别 */
    private String gender;

    /** 个人简介 */
    private String bio;

    /** 注册 IP */
    private String ip;

    /** 省份 */
    private String province;

    /** 城市 */
    private String city;

    /** 关注数 */
    private Integer followingCount;

    /** 粉丝数 */
    private Integer followersCount;

    /** 获赞数 */
    private Integer likesCount;

    /** 收藏数 */
    private Integer favoritesCount;

    /** 找回密码令牌(sha256 十六进制) */
    private String resetToken;

    /** 找回密码令牌过期时间(epoch 毫秒) */
    private Long resetTokenExpires;

    /** 状态:1=正常 0=禁用 */
    private Integer status;

    /** 创建时间(DB 维护,TEXT 原样映射) */
    private String createdAt;

    /** 更新时间(DB 维护,TEXT 原样映射) */
    private String updatedAt;
}
