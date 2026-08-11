package caigou.caigoupetservice.mapper;

import caigou.caigoupetservice.entity.Comment;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 评论数据访问接口(MyBatis 注解式 SQL)
 * 依赖配置 map-underscore-to-camel-case,数据库下划线字段自动映射为驼峰属性
 */
@Mapper
public interface CommentMapper {

    /** 插入评论,回填自增主键 */
    @Insert("INSERT INTO comments (post_id, user_id, parent_id, root_id, content) " +
            "VALUES (#{postId}, #{userId}, #{parentId}, #{rootId}, #{content})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Comment comment);

    /** 按主键查询评论(不筛状态,删除越权判断用) */
    @Select("SELECT * FROM comments WHERE id = #{id}")
    Comment selectById(@Param("id") Long id);

    /** 根评论(一级):post_id 下 parent_id 为空且未删除,时间倒序 */
    @Select("SELECT * FROM comments WHERE post_id = #{postId} AND status = 1 AND parent_id IS NULL " +
            "ORDER BY created_at DESC LIMIT #{offset}, #{limit}")
    List<Comment> listRoots(@Param("postId") Long postId, @Param("offset") int offset, @Param("limit") int limit);

    /** 统计根评论总数 */
    @Select("SELECT COUNT(*) FROM comments WHERE post_id = #{postId} AND status = 1 AND parent_id IS NULL")
    long countRoots(@Param("postId") Long postId);

    /** 子评论(二级):root_id 命中任一根,时间正序 */
    @Select("<script>SELECT * FROM comments WHERE status = 1 AND root_id IN " +
            "<foreach collection='rootIds' item='rid' open='(' separator=',' close=')'>#{rid}</foreach> " +
            "ORDER BY created_at ASC</script>")
    List<Comment> listByRootIds(@Param("rootIds") List<Long> rootIds);

    /** 软删除:状态置 0 */
    @Update("UPDATE comments SET status = 0 WHERE id = #{id}")
    int softDelete(@Param("id") Long id);
}
