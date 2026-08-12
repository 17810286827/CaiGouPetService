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
import caigou.caigoupetservice.util.PluginValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 插件业务:列表(分页/排序/分类/搜索过滤)/分类/详情(isFavorited)/我的插件/上传(zip+manifest 校验)
 * 契约对齐 Express plugins.js:sort 白名单非法回退 download_count,order 仅 ASC/DESC,分类白名单校验
 * 业务异常统一抛 ApiException(status, 中文信息),由全局异常处理器转换为 {error:"..."}
 */
@Slf4j
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

    /** JSON 解析/序列化:ObjectMapper 线程安全,静态复用(与 PetService/PostService 同约定,项目无 Spring 注入 Bean) */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 上传存储目录(来自 application.yaml upload.dir,插件落盘到 upload.dir/plugins/) */
    @Value("${upload.dir:./uploads}")
    private String uploadDir;

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
     * 上传插件 zip:扩展名校验 → 解压找 manifest.json → JSON 解析 → PluginValidator 校验 → 落盘 → 同名更新/新建
     * 契约对齐 Express POST /api/plugins/upload:非 zip→400、缺 manifest→400、JSON 非法→400、校验失败→400{error,details,warnings}
     * 同名同作者→更新返回 200,否则新建返回 201(状态直接置已通过 status=1,与 Express 一致)
     * @return UploadResult{status, body}:controller 依 status 返回 200/201,body 含 message/plugin/warnings
     */
    public UploadResult upload(Long userId, MultipartFile file) {
        // 空文件/缺 file 字段 → 400(controller required=false 兜底到这里)
        if (file == null || file.isEmpty()) {
            throw new ApiException(400, "No file uploaded. Please upload a .zip plugin package.");
        }
        // 扩展名必须是 .zip(镜像 Express multer fileFilter,大小写不敏感)
        String original = file.getOriginalFilename();
        String ext = original == null ? "" : original.substring(original.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (!"zip".equals(ext)) {
            throw new ApiException(400, "Only .zip files are accepted");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            log.error("读取上传插件文件失败", e);
            throw new ApiException(400, "Failed to read plugin package");
        }
        // 解压全部文件并摘出 manifest.json
        ZipContent zip;
        try {
            zip = readZip(bytes);
        } catch (IOException e) {
            log.error("插件 zip 解压失败", e);
            throw new ApiException(400, "Failed to read plugin package");
        }
        if (zip.getManifest() == null) {
            throw new ApiException(400, "manifest.json not found in plugin package");
        }

        // 解析 manifest.json:非合法 JSON → 400 固定文案
        JsonNode manifest;
        try {
            manifest = OBJECT_MAPPER.readTree(zip.getManifest().getContent());
        } catch (IOException e) {
            throw new ApiException(400, "manifest.json is not valid JSON");
        }
        // readTree 对空串/纯空白输入返回 MissingNode 而不抛异常,需显式判定走 400(对齐 Express JSON.parse 抛错口径)
        if (manifest.isMissingNode()) {
            throw new ApiException(400, "manifest.json is not valid JSON");
        }

        // 完整校验(必填字段/entry/文件白名单/内容安全):失败带 details+warnings 返回 400
        PluginValidator.Result validation = PluginValidator.validate(manifest, zip.getFiles());
        if (!validation.isValid()) {
            throw new ApiException(400, "Plugin validation failed", Map.of(
                    "error", "Plugin validation failed",
                    "details", validation.getErrors(),
                    "warnings", validation.getWarnings()));
        }

        // 落盘 uploads/plugins/{uuid}.zip(目录不存在则创建,与 ResourceService 落盘写法一致)
        String storageName = UUID.randomUUID().toString().replace("-", "") + ".zip";
        String storedPath;
        try {
            Path dir = Paths.get(uploadDir).resolve("plugins");
            Files.createDirectories(dir);
            storedPath = dir.resolve(storageName).toAbsolutePath().toString();
            Files.write(Paths.get(storedPath), bytes);
        } catch (IOException e) {
            log.error("插件文件落盘失败", e);
            throw new ApiException(500, "插件文件保存失败");
        }

        // 同名同作者 → 更新返回 200;否则新建返回 201(镜像 Express existing.update / Plugin.create)
        String name = manifest.get("name").asText();
        Plugin existing = pluginMapper.findByNameAndAuthor(name, userId);
        if (existing != null) {
            applyManifest(existing, manifest, storedPath, bytes.length);
            pluginMapper.updateByManifest(existing);
            return new UploadResult(200, Map.of("message", "Plugin updated successfully",
                    "plugin", PluginView.from(existing, authorView(userId), false)));
        }

        Plugin plugin = new Plugin();
        plugin.setAuthorId(userId);
        applyManifest(plugin, manifest, storedPath, bytes.length);
        pluginMapper.insert(plugin);
        String message = "Plugin uploaded successfully" + (validation.getWarnings().isEmpty() ? "" : " (with warnings)");
        return new UploadResult(201, Map.of("message", message,
                "plugin", PluginView.from(plugin, authorView(userId), false),
                "warnings", validation.getWarnings()));
    }

    /**
     * 把 manifest 字段映射到实体(镜像 Express create/update 的字段赋值):
     * version/description/category/tags/icon 缺省值与原 Express 完全一致,status 恒为 1(已通过)
     */
    private void applyManifest(Plugin plugin, JsonNode manifest, String filePath, long fileSize) {
        plugin.setName(manifest.get("name").asText());
        plugin.setVersion(textOr(manifest, "version", "1.0.0"));
        plugin.setDescription(textOr(manifest, "description", ""));
        plugin.setCategory(textOr(manifest, "category", "tool"));
        plugin.setTags(tagsOf(manifest));
        plugin.setIcon(textOrNull(manifest, "icon"));
        plugin.setManifestJson(manifest.toString());
        plugin.setFilePath(filePath);
        plugin.setFileSize((int) fileSize);
        plugin.setStatus(1);
    }

    /** tags 字段转逗号分隔字符串(镜像 Express:数组 join(',')、字符串原样、缺失空串) */
    private String tagsOf(JsonNode manifest) {
        JsonNode tags = manifest.get("tags");
        if (tags == null || tags.isNull()) {
            return "";
        }
        if (tags.isArray()) {
            List<String> items = new ArrayList<>();
            tags.forEach(t -> items.add(t.asText()));
            return String.join(",", items);
        }
        return tags.asText();
    }

    /** 取 manifest 字段文本,缺失/null/非标量返回默认值 */
    private String textOr(JsonNode manifest, String field, String def) {
        JsonNode v = manifest.get(field);
        return (v == null || v.isNull() || !v.isValueNode()) ? def : v.asText();
    }

    /** 取 manifest 字段文本,缺失/null/非标量返回 null(icon 允许空) */
    private String textOrNull(JsonNode manifest, String field) {
        JsonNode v = manifest.get(field);
        return (v == null || v.isNull() || !v.isValueNode()) ? null : v.asText();
    }

    /**
     * 解压 zip:读全部非目录文件(名称+字节),并单独摘出 manifest.json
     * 查找规则镜像 Express:名字含/结尾 manifest.json 即候选,精确 "manifest.json" 优先覆盖
     */
    private ZipContent readZip(byte[] bytes) throws IOException {
        List<PluginValidator.PluginFile> files = new ArrayList<>();
        PluginValidator.PluginFile manifest = null;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                byte[] content = zis.readAllBytes();
                String name = entry.getName();
                files.add(new PluginValidator.PluginFile(name, content));
                if (name.endsWith("manifest.json") || name.contains("manifest.json")) {
                    if (manifest == null || name.equals("manifest.json")) {
                        manifest = new PluginValidator.PluginFile(name, content);
                    }
                }
            }
        }
        return new ZipContent(files, manifest);
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

    /** 上传结果:status 区分 201 新建 / 200 同名更新,body 为最终响应体(controller 据此设状态码) */
    @Getter
    @AllArgsConstructor
    public static class UploadResult {
        /** HTTP 状态码(200=更新 201=新建) */
        private final int status;
        /** 响应体(message/plugin/warnings) */
        private final Map<String, Object> body;
    }

    /** zip 解压产物:全部文件 + 摘出的 manifest.json(可能为 null) */
    @Getter
    @AllArgsConstructor
    private static class ZipContent {
        /** 插件包内全部非目录文件(供 PluginValidator 扫描) */
        private final List<PluginValidator.PluginFile> files;
        /** 命中的 manifest.json(未找到为 null) */
        private final PluginValidator.PluginFile manifest;
    }
}
