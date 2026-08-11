package caigou.caigoupetservice.mapper;

import caigou.caigoupetservice.entity.User;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 用户数据访问接口(MyBatis 注解式 SQL)
 * 依赖配置 map-underscore-to-camel-case,数据库下划线字段自动映射为驼峰属性
 */
@Mapper
public interface UserMapper {

    /** 按主键查询用户(认证拦截器复核用户状态用) */
    @Select("SELECT * FROM users WHERE id = #{id}")
    User findById(@Param("id") Long id);

    /** 按用户名查询用户(登录/注册查重用) */
    @Select("SELECT * FROM users WHERE username = #{username}")
    User findByUsername(@Param("username") String username);

    /** 按用户名或邮箱查询用户(找回密码用) */
    @Select("SELECT * FROM users WHERE username = #{account} OR email = #{account} LIMIT 1")
    User findByUsernameOrEmail(@Param("account") String account);

    /** 插入用户,自动回填自增主键到 user.id */
    @Insert("INSERT INTO users (username, password, nickname, email) " +
            "VALUES (#{username}, #{password}, #{nickname}, #{email})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(User user);

    /** 更新密码(改密/重置密码用) */
    @Update("UPDATE users SET password = #{password} WHERE id = #{id}")
    int updatePassword(@Param("id") Long id, @Param("password") String password);

    /** 保存找回密码令牌与过期时间(expires 为 epoch 毫秒) */
    @Update("UPDATE users SET reset_token = #{token}, reset_token_expires = #{expires} WHERE id = #{id}")
    int saveResetToken(@Param("id") Long id, @Param("token") String token, @Param("expires") Long expires);

    /** 重置密码并清空找回令牌(过期时间置 NULL) */
    @Update("UPDATE users SET password = #{password}, reset_token = NULL, reset_token_expires = NULL WHERE id = #{id}")
    int clearResetToken(@Param("id") Long id, @Param("password") String password);

    /** 关键字搜索用户(排除自己,匹配用户名或昵称,限 20) */
    @Select("SELECT * FROM users WHERE status = 1 AND id != #{selfId} " +
            "AND (username LIKE CONCAT('%', #{q}, '%') OR nickname LIKE CONCAT('%', #{q}, '%')) " +
            "ORDER BY id DESC LIMIT 20")
    List<User> search(@Param("q") String q, @Param("selfId") Long selfId);

    /** 更新用户资料(仅更新传入的非空字段) */
    @Update("<script>UPDATE users SET updated_at = CURRENT_TIMESTAMP" +
            // <if> 动态拼接:仅包含传入的非空列,保证部分更新语义
            "<if test='nickname != null'> , nickname = #{nickname}</if>" +
            "<if test='avatarUrl != null'> , avatar_url = #{avatarUrl}</if>" +
            "<if test='email != null'> , email = #{email}</if>" +
            "<if test='gender != null'> , gender = #{gender}</if>" +
            "<if test='bio != null'> , bio = #{bio}</if>" +
            " WHERE id = #{id}</script>")
    int updateProfile(User user);
}
