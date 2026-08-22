package caigou.caigoupetservice.service;

import caigou.caigoupetservice.dto.ProfileUpdateRequest;
import caigou.caigoupetservice.dto.UserSearchView;
import caigou.caigoupetservice.entity.User;
import caigou.caigoupetservice.exception.ApiException;
import caigou.caigoupetservice.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户业务:搜索/详情/资料更新
 * 业务异常统一抛 ApiException(status, 中文信息),由全局异常处理器转换为 {error:"..."}
 * 当前登录用户ID由 JWT 拦截器写入 request attribute,controller 取出后传入本层
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;

    /**
     * 关键字搜索用户:匹配用户名或昵称,排除当前登录者自己
     * @param q 搜索关键字(空串/空白直接返回空结果,不做无效查询)
     * @param selfId 当前登录用户ID(搜索结果中排除自己)
     * @return 用户搜索结果视图列表(最多 20 条)
     */
    public List<UserSearchView> search(String q, Long selfId) {
        // 契约:q 为空时直接返回空列表,避免无意义的 LIKE 全表扫描
        if (q == null || q.isBlank()) {
            return List.of();
        }
        // 去除首尾空白后再匹配;SQL 侧已按 status=1 过滤并排除自己
        return userMapper.search(q.trim(), selfId).stream().map(UserSearchView::from).toList();
    }

    /**
     * 用户详情视图:组装公开字段返回前端,不泄露密码/找回令牌等敏感信息
     * @param id 目标用户ID
     * @return 公开字段映射,key 与 Express 响应字段一致(snake_case)
     * @throws ApiException 用户不存在时抛 404 "用户不存在"
     */
    public Map<String, Object> getProfile(Long id) {
        User user = userMapper.findById(id);
        if (user == null) {
            throw new ApiException(404, "用户不存在");
        }
        // 用 LinkedHashMap:avatar_url/email 等字段可能为 NULL,
        // Map.of/Map.entry 均禁止空值会抛 NPE;LinkedHashMap 可保留声明顺序,
        // 保证响应字段顺序与 Express 一致
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("id", user.getId());
        profile.put("username", user.getUsername());
        profile.put("nickname", user.getNickname());
        profile.put("avatar_url", user.getAvatarUrl());
        profile.put("email", user.getEmail());
        profile.put("gender", user.getGender());
        profile.put("bio", user.getBio());
        profile.put("ip", user.getIp());
        profile.put("province", user.getProvince());
        profile.put("city", user.getCity());
        // 社区互动计数(注册时默认 0)
        profile.put("following_count", user.getFollowingCount());
        profile.put("followers_count", user.getFollowersCount());
        profile.put("likes_count", user.getLikesCount());
        profile.put("favorites_count", user.getFavoritesCount());
        profile.put("created_at", user.getCreatedAt());
        return profile;
    }

    /**
     * 更新当前登录用户资料:仅更新传入的非空字段,未传字段保持不变
     * @param userId 当前登录用户ID(由拦截器写入 request attribute)
     * @param req 更新请求体(nickname/avatar_url/email/gender/bio/province/city)
     * @return 更新后的最新用户详情(结构同 getProfile)
     */
    public Map<String, Object> updateProfile(Long userId, ProfileUpdateRequest req) {
        // 仅把传入的非空字段塞入实体,Mapper 动态 SQL 只更新非空列
        User user = new User();
        user.setId(userId);
        user.setNickname(req.getNickname());
        user.setAvatarUrl(req.getAvatarUrl());
        user.setEmail(req.getEmail());
        user.setGender(req.getGender());
        user.setBio(req.getBio());
        user.setProvince(req.getProvince());
        user.setCity(req.getCity());
        userMapper.updateProfile(user);
        // 更新后回查一次,返回最新完整资料给前端
        return getProfile(userId);
    }
}
