package caigou.caigoupetservice.mapper;

import caigou.caigoupetservice.entity.Resource;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 资源数据访问接口:上传记录增删查与 MD5 去重
 * 注解方式动态 SQL:整个 SQL 需以 <script> 包裹,<if> 按需追加类型过滤条件
 */
@Mapper
public interface ResourceMapper {

    /** 按主键查询资源(不含已删除) */
    @Select("SELECT * FROM resources WHERE id = #{id} AND status = 1")
    Resource findById(@Param("id") Long id);

    /** 按 MD5 查询已存在资源(去重用,仅活跃记录) */
    @Select("SELECT * FROM resources WHERE md5 = #{md5} AND status = 1 LIMIT 1")
    Resource findByMd5(@Param("md5") String md5);

    /** 插入资源记录,回填自增主键 */
    @Insert("INSERT INTO resources (user_id, type, original_name, storage_path, url, thumbnail_url, size, mime_type, width, height, duration, md5) " +
            "VALUES (#{userId}, #{type}, #{originalName}, #{storagePath}, #{url}, #{thumbnailUrl}, #{size}, #{mimeType}, #{width}, #{height}, #{duration}, #{md5})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Resource resource);

    /** 按用户与类型分页查询活跃资源(type 为 null 时不过滤类型) */
    @Select("<script>SELECT * FROM resources WHERE user_id = #{userId} AND status = 1 " +
            "<if test='type != null'> AND type = #{type}</if> " +
            "ORDER BY created_at DESC LIMIT #{offset}, #{limit}</script>")
    List<Resource> listByUser(@Param("userId") Long userId, @Param("type") Integer type,
                              @Param("offset") int offset, @Param("limit") int limit);

    /** 统计用户活跃资源总数(type 为 null 时不过滤类型) */
    @Select("<script>SELECT COUNT(*) FROM resources WHERE user_id = #{userId} AND status = 1 " +
            "<if test='type != null'> AND type = #{type}</if></script>")
    long countByUser(@Param("userId") Long userId, @Param("type") Integer type);

    /** 软删除资源 */
    @Update("UPDATE resources SET status = 0 WHERE id = #{id}")
    int softDelete(@Param("id") Long id);
}
