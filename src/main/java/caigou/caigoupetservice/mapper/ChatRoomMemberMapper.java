package caigou.caigoupetservice.mapper;

import caigou.caigoupetservice.entity.ChatRoomMember;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 聊天室成员数据访问接口
 * room_id + user_id 有唯一键 uk_room_user,重复加入需先查 find 判断
 */
@Mapper
public interface ChatRoomMemberMapper {

    /** 添加成员,回填自增主键 */
    @Insert("INSERT INTO chat_room_members (room_id, user_id, role, last_read_msg_id) VALUES (#{roomId}, #{userId}, #{role}, #{lastReadMsgId})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ChatRoomMember m);

    /** 按房间+用户精确查询成员(判断是否已在房间内) */
    @Select("SELECT * FROM chat_room_members WHERE room_id=#{roomId} AND user_id=#{userId}")
    ChatRoomMember find(@Param("roomId") Long roomId, @Param("userId") Long userId);

    /** 列出房间全部成员 */
    @Select("SELECT * FROM chat_room_members WHERE room_id=#{roomId}")
    List<ChatRoomMember> listByRoom(@Param("roomId") Long roomId);

    /** 更新成员最后已读消息ID */
    @Update("UPDATE chat_room_members SET last_read_msg_id=#{lastReadMsgId} WHERE id=#{id}")
    int updateLastRead(ChatRoomMember m);
}
