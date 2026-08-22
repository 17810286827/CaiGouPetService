package caigou.caigoupetservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 用户资料更新请求体
 * 字段仅更新传入的非空值,未传字段保持不变
 */
@Data
public class ProfileUpdateRequest {

    /** 昵称 */
    private String nickname;
    /** 头像地址(请求体字段为 avatar_url) */
    @JsonProperty("avatar_url")
    private String avatarUrl;
    /** 邮箱 */
    private String email;
    /** 性别 */
    private String gender;
    /** 个人简介 */
    private String bio;
    /** 省份 */
    private String province;
    /** 城市 */
    private String city;
}
