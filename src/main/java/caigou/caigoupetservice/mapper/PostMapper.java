package caigou.caigoupetservice.mapper;

import caigou.caigoupetservice.entity.Post;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 帖子数据访问接口:帖子 CRUD 与列表分页
 * 依赖配置 map-underscore-to-camel-case,数据库下划线字段自动映射为驼峰属性
 */
@Mapper
public interface PostMapper {

    /** 插入帖子,回填自增主键 */
    @Insert("INSERT INTO posts (user_id, title, content, content_type, summary, cover_url, tags, status) " +
            "VALUES (#{userId}, #{title}, #{content}, #{contentType}, #{summary}, #{coverUrl}, #{tags}, #{status})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Post post);

    /** 按主键查询帖子(不筛状态,编辑/删除越权判断用) */
    @Select("SELECT * FROM posts WHERE id = #{id}")
    Post selectById(@Param("id") Long id);

    /** 查询可见帖子(状态=1) */
    @Select("SELECT * FROM posts WHERE id = #{id} AND status = 1")
    Post selectVisibleById(@Param("id") Long id);

    /** 首页流:公开帖子,置顶优先再按时间倒序 */
    @Select("SELECT * FROM posts WHERE status = 1 ORDER BY is_top DESC, created_at DESC LIMIT #{offset}, #{limit}")
    List<Post> listFeed(@Param("offset") int offset, @Param("limit") int limit);

    /** 统计公开帖子总数 */
    @Select("SELECT COUNT(*) FROM posts WHERE status = 1")
    long countFeed();

    /** 更新帖子内容(仅非空字段) */
    @Update("<script>UPDATE posts SET updated_at = CURRENT_TIMESTAMP" +
            "<if test='title != null'> , title = #{title}</if>" +
            "<if test='content != null'> , content = #{content}</if>" +
            "<if test='contentType != null'> , content_type = #{contentType}</if>" +
            "<if test='summary != null'> , summary = #{summary}</if>" +
            "<if test='coverUrl != null'> , cover_url = #{coverUrl}</if>" +
            "<if test='tags != null'> , tags = #{tags}</if>" +
            " WHERE id = #{id}</script>")
    int update(Post post);

    /** 软删除:状态置 2 + 删除时间 */
    @Update("UPDATE posts SET status = 2, deleted_at = CURRENT_TIMESTAMP WHERE id = #{id}")
    int softDelete(@Param("id") Long id);

    /** 浏览量自增 */
    @Update("UPDATE posts SET view_count = view_count + 1 WHERE id = #{id}")
    int incrementView(@Param("id") Long id);

    /** 点赞数自增/自减 */
    @Update("UPDATE posts SET like_count = like_count + #{delta} WHERE id = #{id}")
    int changeLikeCount(@Param("id") Long id, @Param("delta") int delta);

    /** 评论数自增/自减 */
    @Update("UPDATE posts SET comment_count = comment_count + #{delta} WHERE id = #{id}")
    int changeCommentCount(@Param("id") Long id, @Param("delta") int delta);

    /** 用户公开帖子列表 */
    @Select("SELECT * FROM posts WHERE user_id = #{userId} AND status = 1 ORDER BY created_at DESC LIMIT #{offset}, #{limit}")
    List<Post> listByUser(@Param("userId") Long userId, @Param("offset") int offset, @Param("limit") int limit);

    /** 统计用户公开帖子总数 */
    @Select("SELECT COUNT(*) FROM posts WHERE user_id = #{userId} AND status = 1")
    long countByUser(@Param("userId") Long userId);
}
