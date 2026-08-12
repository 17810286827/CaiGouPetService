package caigou.caigoupetservice.mapper;

import caigou.caigoupetservice.entity.PetState;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 宠物状态数据访问接口
 * 每用户一条记录(唯一键 uk_user),按 userId 查/插/更
 */
@Mapper
public interface PetStateMapper {

    /** 按用户ID查询宠物状态(不存在返回 null) */
    @Select("SELECT * FROM pet_states WHERE user_id=#{userId}")
    PetState findByUserId(@Param("userId") Long userId);

    /** 插入宠物状态,回填自增主键 */
    @Insert("INSERT INTO pet_states (user_id, emotion_state, personality, last_sync_at) VALUES (#{userId}, #{emotionState}, #{personality}, #{lastSyncAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(PetState s);

    /** 更新宠物状态(情绪/性格/最后同步时间) */
    @Update("UPDATE pet_states SET emotion_state=#{emotionState}, personality=#{personality}, last_sync_at=#{lastSyncAt} WHERE id=#{id}")
    int update(PetState s);
}
