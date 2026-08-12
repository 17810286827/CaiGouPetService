package caigou.caigoupetservice.service;

import caigou.caigoupetservice.dto.PluginView;
import caigou.caigoupetservice.dto.UserView;
import caigou.caigoupetservice.entity.Plugin;
import caigou.caigoupetservice.entity.User;
import caigou.caigoupetservice.exception.ApiException;
import caigou.caigoupetservice.mapper.PluginFavoriteMapper;
import caigou.caigoupetservice.mapper.PluginMapper;
import caigou.caigoupetservice.mapper.UserMapper;
import caigou.caigoupetservice.util.Pagination;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 插件业务:列表(分页/排序/分类/搜索过滤)/分类/详情(isFavorited)/我的插件
 * 契约对齐 Express plugins.js:sort 白名单非法回退 download_count,order 仅 ASC/DESC,分类白名单校验
 * 业务异常统一抛 ApiException(status, 中文信息),由全局异常处理器转换为 {error:"..."}
 */
@Service
@RequiredArgsConstructor
public class PluginService {

    /** 分类白名单:与 Express utils/plugin-validator.js 的 VALID_CATEGORIES 完全一致 */
    private static final List<String> CATEGORIES = List.of("tool", "game", "utility", "social", "customization", "other");

    /** 排序字段白名单:非法值回退 download_count */
    private static final List<String> SORT_FIELDS = List.of("download_count", "favorite_count", "created_at", "name", "version");

    private final PluginMapper pluginMapper;
    private final PluginFavoriteMapper pluginFavoriteMapper;
    private final UserMapper userMapper;
    private final JwtService jwtService;

    /**
     * 插件列表:分页钳制后过滤/排序查询,返回 {plugins, pagination:{page,limit,total,totalPages}}
     */
    public Map<String, Object> list(int page, int limit, String sort, String order, String category, String search) {
        // 分页钳制:page 最小 1、limit 上限 100,避免负 offset 触发 SQL 500(对齐其它模块)
        page = Pagination.clampPage(page);
        limit = Pagination.clampLimit(limit);
        // 排序白名单:非法 sort 回退 download_count;order 仅 ASC 生效,其余一律 DESC(对齐 Express)
        String sortField = SORT_FIELDS.contains(sort) ? sort : "download_count";
        String sortOrder = "ASC".equals(order) ? "ASC" : "DESC";
        // 分类白名单校验:非法分类忽略过滤(对齐 Express 仅对合法分类生效,返回全部)
        String cat = (category != null && CATEGORIES.contains(category)) ? category : null;
        String keyword = (search == null || search.isBlank()) ? null : search;
        int offset = (page - 1) * limit;
        // 列表不计算 isFavorited(对齐 Express list 不内嵌),作者逐条组装
        List<PluginView> plugins = pluginMapper.list(cat, keyword, sortField, sortOrder, offset, limit)
                .stream().map(p -> PluginView.from(p, authorView(p.getAuthorId()), false)).toList();
        long total = pluginMapper.count(cat, keyword);
        int totalPages = (int) Math.ceil((double) total / limit);
        return Map.of("plugins", plugins,
                "pagination", Map.of("page", page, "limit", limit, "total", total, "totalPages", totalPages));
    }

    /** 可用分类列表(白名单原样返回,对齐 Express /api/plugins/categories) */
    public List<String> categories() {
        return CATEGORIES;
    }

    /**
     * 插件详情:不存在返回 404;带 userId 时查询该用户是否已收藏(isFavorited),未登录/无效 token 传 null 则恒 false
     */
    public PluginView detail(Long id, Long userId) {
        Plugin plugin = pluginMapper.findById(id);
        if (plugin == null) {
            throw new ApiException(404, "Plugin not found");
        }
        boolean isFavorited = userId != null && pluginFavoriteMapper.find(userId, id) != null;
        return PluginView.from(plugin, authorView(plugin.getAuthorId()), isFavorited);
    }

    /** 我的插件:按作者查全部(含待审/拒绝),时间倒序,对齐 Express /api/plugins/my */
    public List<PluginView> listMy(Long userId) {
        return pluginMapper.listByAuthor(userId).stream()
                .map(p -> PluginView.from(p, authorView(p.getAuthorId()), false)).toList();
    }

    /**
     * 从 Authorization 头解析可选 userId(公开详情接口计算 isFavorited 用):
     * 无头/无 Bearer 前缀/无效 token 返回 null;有效 token 再复核用户存在且未禁用
     * (与 JwtAuthInterceptor 语义一致:禁用/已删用户的 token 视为未登录,不计算收藏态)
     */
    public Long resolveOptionalUserId(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        try {
            Long userId = jwtService.parseUserId(authorization.substring("Bearer ".length()));
            User user = userMapper.findById(userId);
            return (user != null && user.getStatus() != null && user.getStatus() == 1) ? userId : null;
        } catch (ApiException e) {
            // 无效/过期 token 按未登录处理,详情仍正常返回
            return null;
        }
    }

    /** 查询作者视图:findById 不筛 status,作者被禁用仍展示其历史插件;作者行缺失返回 null */
    private UserView authorView(Long authorId) {
        User author = userMapper.findById(authorId);
        return author == null ? null : UserView.from(author);
    }
}
