package caigou.caigoupetservice.entity;

import lombok.Data;

/**
 * 插件实体,对应 plugins 表
 * tags 为逗号分隔字符串(DB 原样存取);manifest_json 存插件清单 JSON 字符串
 * created_at/updated_at 由数据库维护,不参与 insert
 */
@Data
public class Plugin {

    /** 主键(自增) */
    private Long id;

    /** 插件名称 */
    private String name;

    /** 版本号(默认 1.0.0) */
    private String version;

    /** 插件描述 */
    private String description;

    /** 作者ID(外键→users.id) */
    private Long authorId;

    /** 分类(白名单:tool/game/utility/social/customization/other) */
    private String category;

    /** 标签(逗号分隔) */
    private String tags;

    /** 图标URL */
    private String icon;

    /** 下载数 */
    private Integer downloadCount;

    /** 收藏数 */
    private Integer favoriteCount;

    /** 插件清单JSON字符串 */
    private String manifestJson;

    /** 插件文件路径 */
    private String filePath;

    /** 文件字节数 */
    private Integer fileSize;

    /** 状态:0=待审 1=通过 2=拒绝 */
    private Integer status;

    /** 审核意见 */
    private String reviewComment;

    /** 创建时间(DB 维护,TEXT 原样映射) */
    private String createdAt;

    /** 更新时间(DB 维护,TEXT 原样映射) */
    private String updatedAt;
}
