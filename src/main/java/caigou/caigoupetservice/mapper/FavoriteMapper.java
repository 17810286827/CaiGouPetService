package caigou.caigoupetservice.mapper;

import caigou.caigoupetservice.entity.Favorite;
import caigou.caigoupetservice.entity.Post;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 收藏数据访问接口:幂等插入/删除与按用户反查帖子(与 LikeMapper 同构)
 */
@Mapper
public interface FavoriteMapper {

    /** 插入收藏记录,回填自增主键(唯一约束兜底重复) */
    @Insert("INSERT INTO favorites (user_id, post_id) VALUES (#{userId}, #{postId})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Favorite favorite);

    /** 查询指定用户对指定帖子的收藏记录 */
    @Select("SELECT * FROM favorites WHERE user_id = #{userId} AND post_id = #{postId}")
    Favorite find(@Param("userId") Long userId, @Param("postId") Long postId);

    /** 删除收藏记录 */
    @Delete("DELETE FROM favorites WHERE user_id = #{userId} AND post_id = #{postId}")
    int delete(@Param("userId") Long userId, @Param("postId") Long postId);

    /** 分页查询用户收藏过的可见帖子(JOIN posts,时间倒序) */
    @Select("SELECT p.* FROM posts p JOIN favorites f ON p.id = f.post_id " +
            "WHERE f.user_id = #{userId} AND p.status = 1 ORDER BY f.created_at DESC LIMIT #{offset}, #{limit}")
    List<Post> listUserPosts(@Param("userId") Long userId, @Param("offset") int offset, @Param("limit") int limit);

    /** 统计用户收藏过的可见帖子总数 */
    @Select("SELECT COUNT(*) FROM posts p JOIN favorites f ON p.id = f.post_id WHERE f.user_id = #{userId} AND p.status = 1")
    long countUserPosts(@Param("userId") Long userId);
}
