package caigou.caigoupetservice.mapper;

import caigou.caigoupetservice.entity.ChatRoom;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 聊天室数据访问接口
 * 依赖配置 map-underscore-to-camel-case,数据库下划线字段自动映射为驼峰属性
 */
@Mapper
public interface ChatRoomMapper {

    /** 创建聊天室,回填自增主键 */
    @Insert("INSERT INTO chat_rooms (type, name, avatar_url, created_by) VALUES (#{type}, #{name}, #{avatarUrl}, #{createdBy})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ChatRoom room);

    /** 按主键查询聊天室 */
    @Select("SELECT * FROM chat_rooms WHERE id = #{id}")
    ChatRoom findById(@Param("id") Long id);

    /**
     * 私聊幂等:查已存在 type=1 且同时含请求双方成员的房间(不要求当前用户是创建者)
     * 对齐 Express chat.js 双向幂等——任何一方再发起私聊都复用同一房间
     * m1.user_id<>m2.user_id 防自匹配(同一个人同时出现在两个 JOIN 中)
     */
    @Select("SELECT DISTINCT cr.* FROM chat_rooms cr JOIN chat_room_members m1 ON m1.room_id=cr.id JOIN chat_room_members m2 ON m2.room_id=cr.id " +
            "WHERE cr.type=1 AND m1.user_id=#{creatorId} AND m2.user_id=#{otherId} AND m1.user_id<>m2.user_id LIMIT 1")
    ChatRoom findPrivateRoom(@Param("creatorId") Long creatorId, @Param("otherId") Long otherId);

    /** 按用户列出其参与的全部房间,按更新时间倒序 */
    @Select("SELECT cr.* FROM chat_rooms cr JOIN chat_room_members m ON m.room_id=cr.id WHERE m.user_id=#{userId} ORDER BY cr.updated_at DESC")
    List<ChatRoom> listByUserId(@Param("userId") Long userId);
}
