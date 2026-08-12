package caigou.caigoupetservice.entity;

import lombok.Data;

/**
 * 插件收藏实体,对应 plugin_favorites 表
 * (user_id, plugin_id) 有唯一键,重复收藏由 DB 兜底
 */
@Data
public class PluginFavorite {

    /** 主键(自增) */
    private Long id;

    /** 收藏者ID */
    private Long userId;

    /** 插件ID */
    private Long pluginId;

    /** 创建时间(DB 维护) */
    private String createdAt;
}
