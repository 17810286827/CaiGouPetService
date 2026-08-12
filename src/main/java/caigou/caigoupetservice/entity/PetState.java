package caigou.caigoupetservice.entity;

import lombok.Data;

/**
 * 宠物状态实体,对应 pet_states 表(每用户一条,唯一键 uk_user)
 * emotionState/personality 为 JSON 对象,存字符串原样读写,JSON 解析由 service 层完成
 */
@Data
public class PetState {

    /** 主键(自增) */
    private Long id;
    /** 用户ID(每用户一条,外键→users.id) */
    private Long userId;
    /** 情绪状态对象(JSON 字符串) */
    private String emotionState;
    /** 性格对象(JSON 字符串) */
    private String personality;
    /** 最后同步时间(可空) */
    private String lastSyncAt;
    /** 创建时间(DB 维护) */
    private String createdAt;
    /** 更新时间(DB 维护) */
    private String updatedAt;
}
