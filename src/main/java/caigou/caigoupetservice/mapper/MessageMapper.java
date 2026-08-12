package caigou.caigoupetservice.mapper;

import caigou.caigoupetservice.entity.Message;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 聊天消息数据访问接口
 * client_msg_id 有唯一键 uk_sender_client,重复 insert 会抛 DuplicateKeyException 由调用方处理
 */
@Mapper
public interface MessageMapper {

    /**
     * 插入消息,回填自增主键
     * 重复 client_msg_id 抛 DuplicateKeyException
     */
    @Insert("INSERT INTO messages (room_id, sender_id, msg_type, content, resource_id, reply_to, status, client_msg_id) " +
            "VALUES (#{roomId}, #{senderId}, #{msgType}, #{content}, #{resourceId}, #{replyTo}, #{status}, #{clientMsgId})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Message msg);

    /** 向下翻页:取 before 之前的正常消息(游标分页,id 倒序) */
    @Select("SELECT * FROM messages WHERE room_id=#{roomId} AND status=1 AND id < #{before} ORDER BY id DESC LIMIT #{limit}")
    List<Message> listBefore(@Param("roomId") Long roomId, @Param("before") Long before, @Param("limit") int limit);

    /** 首页加载:取房间最近 limit 条正常消息 */
    @Select("SELECT * FROM messages WHERE room_id=#{roomId} AND status=1 ORDER BY id DESC LIMIT #{limit}")
    List<Message> listLatest(@Param("roomId") Long roomId, @Param("limit") int limit);

    /** 查询房间内最近 3 条宠物互动系统消息(msg_type=5,用于串门/宠物互动上下文) */
    @Select("SELECT * FROM messages WHERE room_id=#{roomId} AND sender_id=#{senderId} AND msg_type=5 ORDER BY created_at DESC LIMIT 3")
    List<Message> findRecentPetInteract(@Param("roomId") Long roomId, @Param("senderId") Long senderId);

    /** 按主键查询消息 */
    @Select("SELECT * FROM messages WHERE id=#{id}")
    Message findById(@Param("id") Long id);
}
