package caigou.caigoupetservice.mapper;

import caigou.caigoupetservice.entity.PetVisitSetting;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 宠物串门设置数据访问接口
 * roomId 为空为全局设置,非空为房间级覆盖;deleteRoom 删除房间级覆盖后回落到全局
 */
@Mapper
public interface PetVisitSettingMapper {

    /** 查询全局设置(room_id IS NULL) */
    @Select("SELECT * FROM pet_visit_settings WHERE user_id=#{userId} AND room_id IS NULL")
    PetVisitSetting findGlobal(@Param("userId") Long userId);

    /** 查询指定房间的设置 */
    @Select("SELECT * FROM pet_visit_settings WHERE user_id=#{userId} AND room_id=#{roomId}")
    PetVisitSetting findRoom(@Param("userId") Long userId, @Param("roomId") Long roomId);

    /** 列出用户全部串门设置(全局 + 各房间) */
    @Select("SELECT * FROM pet_visit_settings WHERE user_id=#{userId}")
    List<PetVisitSetting> listByUser(@Param("userId") Long userId);

    /** 插入串门设置,回填自增主键 */
    @Insert("INSERT INTO pet_visit_settings (user_id, room_id, allow) VALUES (#{userId}, #{roomId}, #{allow})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(PetVisitSetting s);

    /** 更新串门设置(是否允许) */
    @Update("UPDATE pet_visit_settings SET allow=#{allow} WHERE id=#{id}")
    int update(PetVisitSetting s);

    /** 删除指定房间的覆盖设置(删除后回落为全局设置) */
    @Delete("DELETE FROM pet_visit_settings WHERE user_id=#{userId} AND room_id=#{roomId}")
    int deleteRoom(@Param("userId") Long userId, @Param("roomId") Long roomId);
}
