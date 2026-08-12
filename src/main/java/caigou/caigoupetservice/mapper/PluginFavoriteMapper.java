package caigou.caigoupetservice.mapper;

import caigou.caigoupetservice.entity.PluginFavorite;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 插件收藏数据访问接口:查询/收藏/取消收藏/按插件清理
 */
@Mapper
public interface PluginFavoriteMapper {

    /** 查询指定用户对指定插件的收藏记录(判 isFavorited 用) */
    @Select("SELECT * FROM plugin_favorites WHERE user_id = #{userId} AND plugin_id = #{pluginId}")
    PluginFavorite find(@Param("userId") Long userId, @Param("pluginId") Long pluginId);

    /** 插入收藏记录,回填自增主键 */
    @Insert("INSERT INTO plugin_favorites (user_id, plugin_id) VALUES (#{userId}, #{pluginId})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(PluginFavorite favorite);

    /** 取消收藏 */
    @Delete("DELETE FROM plugin_favorites WHERE user_id = #{userId} AND plugin_id = #{pluginId}")
    int deleteByUserPlugin(@Param("userId") Long userId, @Param("pluginId") Long pluginId);

    /** 按插件清理收藏(删除插件时级联清理) */
    @Delete("DELETE FROM plugin_favorites WHERE plugin_id = #{pluginId}")
    int deleteByPlugin(@Param("pluginId") Long pluginId);
}
