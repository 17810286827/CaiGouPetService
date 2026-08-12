package caigou.caigoupetservice.mapper;

import caigou.caigoupetservice.entity.Plugin;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 插件数据访问接口(MyBatis 注解式 SQL)
 * list/count 动态拼接:category 与 search 按需追加过滤,排序字段经 ${} 直插(取值来自 service 白名单,无注入风险)
 */
@Mapper
public interface PluginMapper {

    /** 按主键查询插件(不筛状态,镜像 Express findByPk:详情可见待审/拒绝插件) */
    @Select("SELECT * FROM plugins WHERE id = #{id}")
    Plugin findById(@Param("id") Long id);

    /** 分页查询已通过插件:按分类/关键字过滤,动态排序,offset+limit 分页 */
    @Select("<script>SELECT * FROM plugins WHERE status = 1" +
            "<if test='category != null'> AND category = #{category}</if>" +
            "<if test='search != null and search != \"\"'> AND (name LIKE CONCAT('%', #{search}, '%') " +
            "OR description LIKE CONCAT('%', #{search}, '%') OR tags LIKE CONCAT('%', #{search}, '%'))</if>" +
            " ORDER BY ${sortField} ${sortOrder} LIMIT #{offset}, #{limit}</script>")
    List<Plugin> list(@Param("category") String category, @Param("search") String search,
                      @Param("sortField") String sortField, @Param("sortOrder") String sortOrder,
                      @Param("offset") int offset, @Param("limit") int limit);

    /** 统计已通过插件总数(list 的 count 版本,where 条件一致) */
    @Select("<script>SELECT COUNT(*) FROM plugins WHERE status = 1" +
            "<if test='category != null'> AND category = #{category}</if>" +
            "<if test='search != null and search != \"\"'> AND (name LIKE CONCAT('%', #{search}, '%') " +
            "OR description LIKE CONCAT('%', #{search}, '%') OR tags LIKE CONCAT('%', #{search}, '%'))</if>" +
            "</script>")
    long count(@Param("category") String category, @Param("search") String search);

    /** 按作者查询插件(我的插件,时间倒序,不筛状态) */
    @Select("SELECT * FROM plugins WHERE author_id = #{authorId} ORDER BY created_at DESC")
    List<Plugin> listByAuthor(@Param("authorId") Long authorId);

    /** 按名称+作者查询插件(上传幂等复用用) */
    @Select("SELECT * FROM plugins WHERE name = #{name} AND author_id = #{authorId}")
    Plugin findByNameAndAuthor(@Param("name") String name, @Param("authorId") Long authorId);

    /** 插入插件,回填自增主键 */
    @Insert("INSERT INTO plugins (name, version, description, author_id, category, tags, icon, manifest_json, file_path, file_size, status) " +
            "VALUES (#{name}, #{version}, #{description}, #{authorId}, #{category}, #{tags}, #{icon}, #{manifestJson}, #{filePath}, #{fileSize}, #{status})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Plugin plugin);

    /** 按 manifest 更新插件(上传覆盖更新,状态强制置为已通过) */
    @Update("UPDATE plugins SET version = #{version}, description = #{description}, category = #{category}, " +
            "tags = #{tags}, icon = #{icon}, manifest_json = #{manifestJson}, file_path = #{filePath}, " +
            "file_size = #{fileSize}, status = 1 WHERE id = #{id}")
    int updateByManifest(Plugin plugin);

    /** 下载数自增 */
    @Update("UPDATE plugins SET download_count = download_count + 1 WHERE id = #{id}")
    int incrementDownload(@Param("id") Long id);

    /** 收藏数自增 */
    @Update("UPDATE plugins SET favorite_count = favorite_count + 1 WHERE id = #{id}")
    int incrementFavorite(@Param("id") Long id);

    /** 收藏数自减 */
    @Update("UPDATE plugins SET favorite_count = favorite_count - 1 WHERE id = #{id}")
    int decrementFavorite(@Param("id") Long id);

    /** 物理删除插件 */
    @Delete("DELETE FROM plugins WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}
