package caigou.caigoupetservice.dto;

import caigou.caigoupetservice.entity.User;

/**
 * 用户安全视图:返回给前端的用户信息,不含密码等敏感字段
 * 字段名直接使用下划线(avatar_url),与 Express 响应字段一致,无需依赖全局命名策略
 */
public record UserView(Long id, String username, String nickname, String avatar_url) {

    /**
     * 从实体构造视图
     */
    public static UserView from(User user) {
        return new UserView(user.getId(), user.getUsername(), user.getNickname(), user.getAvatarUrl());
    }
}
