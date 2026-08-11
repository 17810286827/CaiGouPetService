package caigou.caigoupetservice.mapper;

import caigou.caigoupetservice.entity.Follow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 关注数据访问接口:幂等关注/取关与粉丝/关注列表
 */
@Mapper
public interface FollowMapper {

    /** 插入关注记录(user_id=被关注者, follower_id=关注者) */
    @Insert("INSERT INTO follows (user_id, follower_id) VALUES (#{userId}, #{followerId})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Follow follow);

    /** 查询是否已关注 */
    @Select("SELECT * FROM follows WHERE user_id = #{userId} AND follower_id = #{followerId}")
    Follow find(@Param("userId") Long userId, @Param("followerId") Long followerId);

    /** 删除关注记录 */
    @Delete("DELETE FROM follows WHERE user_id = #{userId} AND follower_id = #{followerId}")
    int delete(@Param("userId") Long userId, @Param("followerId") Long followerId);

    /** 粉丝列表:被关注者=userId 的记录 */
    @Select("SELECT * FROM follows WHERE user_id = #{userId} ORDER BY created_at DESC LIMIT #{offset}, #{limit}")
    List<Follow> listFollowers(@Param("userId") Long userId, @Param("offset") int offset, @Param("limit") int limit);

    /** 统计粉丝总数 */
    @Select("SELECT COUNT(*) FROM follows WHERE user_id = #{userId}")
    long countFollowers(@Param("userId") Long userId);

    /** 关注列表:关注者=userId 的记录 */
    @Select("SELECT * FROM follows WHERE follower_id = #{userId} ORDER BY created_at DESC LIMIT #{offset}, #{limit}")
    List<Follow> listFollowing(@Param("userId") Long userId, @Param("offset") int offset, @Param("limit") int limit);

    /** 统计关注总数 */
    @Select("SELECT COUNT(*) FROM follows WHERE follower_id = #{userId}")
    long countFollowing(@Param("userId") Long userId);
}
