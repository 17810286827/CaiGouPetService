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

    /** 查询房间内最近 3 条宠物互动系统消息(msg_type=5 且 status=1,用于串门/宠物互动冷却校验) */
    @Select("SELECT * FROM messages WHERE room_id=#{roomId} AND sender_id=#{senderId} AND msg_type=5 AND status=1 ORDER BY created_at DESC LIMIT 3")
    List<Message> findRecentPetInteract(@Param("roomId") Long roomId, @Param("senderId") Long senderId);

    /** 查询最近一条宠物互动消息的创建时间(epoch 毫秒);UNIX_TIMESTAMP 在 DB 侧换算,规避 JDBC 时间串与 JVM 时区不一致 */
    @Select("SELECT UNIX_TIMESTAMP(created_at) * 1000 FROM messages WHERE room_id=#{roomId} AND sender_id=#{senderId} " +
            "AND msg_type=5 AND status=1 AND content LIKE CONCAT('%', 'pet_interact', '%') ORDER BY created_at DESC LIMIT 1")
    Long findLastPetInteractTime(@Param("roomId") Long roomId, @Param("senderId") Long senderId);

    /** 按 client_msg_id 精确查消息(幂等去重:同一发送者同一事件 id 重复提交时复用) */
    @Select("SELECT * FROM messages WHERE room_id=#{roomId} AND sender_id=#{senderId} AND client_msg_id=#{clientMsgId} LIMIT 1")
    Message findByClientMsgId(@Param("roomId") Long roomId, @Param("senderId") Long senderId, @Param("clientMsgId") String clientMsgId);

    /** 按主键查询消息 */
    @Select("SELECT * FROM messages WHERE id=#{id}")
    Message findById(@Param("id") Long id);
}
