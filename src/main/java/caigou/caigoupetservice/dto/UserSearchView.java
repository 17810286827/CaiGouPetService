package caigou.caigoupetservice.dto;

import caigou.caigoupetservice.entity.User;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 用户搜索结果视图:search 接口返回的轻量用户信息
 * 字段名直接使用下划线(avatar_url),与 Express 响应字段一致
 */
@Data
@AllArgsConstructor
public class UserSearchView {

    /** 用户ID */
    private Long id;
    /** 用户名 */
    private String username;
    /** 昵称 */
    private String nickname;
    /** 头像地址 */
    private String avatar_url;
    /** 性别 */
    private String gender;

    /** 从实体构造搜索视图 */
    public static UserSearchView from(User user) {
        return new UserSearchView(user.getId(), user.getUsername(), user.getNickname(), user.getAvatarUrl(), user.getGender());
    }
}
