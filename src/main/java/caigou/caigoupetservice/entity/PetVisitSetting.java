package caigou.caigoupetservice.entity;

import lombok.Data;

/**
 * 宠物串门设置实体,对应 pet_visit_settings 表
 * roomId 为空表示全局设置,非空表示针对特定聊天室覆盖(唯一键 uk_user_room)
 */
@Data
public class PetVisitSetting {

    /** 主键(自增) */
    private Long id;
    /** 用户ID(外键→users.id) */
    private Long userId;
    /** 聊天室ID(可空,空=全局设置) */
    private Long roomId;
    /** 是否允许串门:1=允许 0=拒绝 */
    private Integer allow;
    /** 创建时间(DB 维护) */
    private String createdAt;
    /** 更新时间(DB 维护) */
    private String updatedAt;
}
