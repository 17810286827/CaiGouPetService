package caigou.caigoupetservice.entity;

import lombok.Data;

/**
 * 帖子实体,对应 posts 表
 * tags 字段为 JSON 数组字符串(DB 原样存取),转数组由 PostView 层完成
 * created_at/updated_at 由数据库维护,不参与 insert
 */
@Data
public class Post {

    /** 主键(自增) */
    private Long id;
    /** 作者ID(外键→users.id) */
    private Long userId;
    /** 标题(可空) */
    private String title;
    /** 正文(长文本) */
    private String content;
    /** 内容类型:0=纯文本 1=markdown 2=富文本 */
    private Integer contentType;
    /** 摘要(可空) */
    private String summary;
    /** 封面图URL(可空) */
    private String coverUrl;
    /** 标签数组(JSON 字符串,如 ["标签1","标签2"]) */
    private String tags;
    /** 状态:0=草稿 1=公开 2=删除 */
    private Integer status;
    /** 可见性:1公开 2仅粉丝 3仅好友 4仅自己 */
    private Integer visibility;
    /** 浏览数 */
    private Integer viewCount;
    /** 点赞数 */
    private Integer likeCount;
    /** 评论数 */
    private Integer commentCount;
    /** 是否置顶:0=否 1=是 */
    private Integer isTop;
    /** 软删除时间(可空) */
    private String deletedAt;
    /** 创建时间(DB 维护,TEXT 原样映射) */
    private String createdAt;
    /** 更新时间(DB 维护,TEXT 原样映射) */
    private String updatedAt;
}
