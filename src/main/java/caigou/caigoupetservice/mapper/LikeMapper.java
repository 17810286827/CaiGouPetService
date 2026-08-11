package caigou.caigoupetservice.mapper;

import caigou.caigoupetservice.entity.Like;
import caigou.caigoupetservice.entity.Post;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 点赞数据访问接口:幂等插入/删除与按用户反查帖子
 */
@Mapper
public interface LikeMapper {

    /** 插入点赞记录,回填自增主键(唯一约束兜底重复) */
    @Insert("INSERT INTO likes (user_id, post_id) VALUES (#{userId}, #{postId})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Like like);

    /** 查询指定用户对指定帖子的点赞记录 */
    @Select("SELECT * FROM likes WHERE user_id = #{userId} AND post_id = #{postId}")
    Like find(@Param("userId") Long userId, @Param("postId") Long postId);

    /** 删除点赞记录 */
    @Delete("DELETE FROM likes WHERE user_id = #{userId} AND post_id = #{postId}")
    int delete(@Param("userId") Long userId, @Param("postId") Long postId);

    /** 分页查询用户点赞过的可见帖子(JOIN posts,时间倒序) */
    @Select("SELECT p.* FROM posts p JOIN likes l ON p.id = l.post_id " +
            "WHERE l.user_id = #{userId} AND p.status = 1 ORDER BY l.created_at DESC LIMIT #{offset}, #{limit}")
    List<Post> listUserPosts(@Param("userId") Long userId, @Param("offset") int offset, @Param("limit") int limit);

    /** 统计用户点赞过的可见帖子总数 */
    @Select("SELECT COUNT(*) FROM posts p JOIN likes l ON p.id = l.post_id WHERE l.user_id = #{userId} AND p.status = 1")
    long countUserPosts(@Param("userId") Long userId);
}
