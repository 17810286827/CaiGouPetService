package caigou.caigoupetservice.service;

import caigou.caigoupetservice.dto.PageView;
import caigou.caigoupetservice.dto.ResourceView;
import caigou.caigoupetservice.entity.Resource;
import caigou.caigoupetservice.exception.ApiException;
import caigou.caigoupetservice.mapper.ResourceMapper;
import caigou.caigoupetservice.util.Pagination;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 资源业务:文件上传(类型校验/MD5 去重/落盘)/列表/详情/软删除
 * 业务异常统一抛 ApiException(status, 中文信息),由全局异常处理器转换为 {error:"..."}
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceService {

    /** 单文件大小上限:10MB(对齐 Express multipart 限制) */
    private static final long MAX_SIZE = 10L * 1024 * 1024;
    /** 允许上传的扩展名(对齐 Express config 白名单) */
    private static final Set<String> ALLOWED_EXT = Set.of(
            "jpg", "jpeg", "png", "gif", "webp", "mp4", "webm", "pdf", "zip", "mp3");

    private final ResourceMapper resourceMapper;

    /** 上传目录(来自 application.yaml upload.dir,与 WebConfig 静态映射共用) */
    @Value("${upload.dir:./uploads}")
    private String uploadDir;

    /**
     * 上传文件:校验大小与类型 → 计算 MD5 去重 → 落盘 → 入库
     * @param userId 当前登录用户ID(由拦截器写入 request attribute)
     * @param file multipart 上传文件(字段名 file)
     * @return 资源视图(MD5 命中时返回旧记录,不重复落盘)
     */
    public ResourceView upload(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(400, "请选择文件");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new ApiException(413, "文件大小超出限制");
        }
        String original = file.getOriginalFilename();
        // 提取扩展名并归一为小写,便于与白名单比对
        String ext = original == null ? "" : original.substring(original.lastIndexOf('.') + 1).toLowerCase();
        // 扩展名不在白名单则直接拒绝,消息带上 .ext 方便前端提示
        if (!ALLOWED_EXT.contains(ext)) {
            throw new ApiException(400, "不支持的文件类型: ." + ext);
        }
        String md5 = md5Hex(file);
        // MD5 去重:命中已有活跃记录则返回旧记录
        Resource existing = resourceMapper.findByMd5(md5);
        if (existing != null) {
            return ResourceView.from(existing);
        }
        // 通过全部校验:生成随机存储名并落盘到 upload.dir 目录
        String storageName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        try {
            Path dir = Paths.get(uploadDir);
            Files.createDirectories(dir);
            file.transferTo(dir.resolve(storageName).toAbsolutePath());
        } catch (IOException e) {
            log.error("文件落盘失败", e);
            throw new ApiException(500, "上传失败");
        }
        // 组装资源记录并入库,自增主键回填到 resource.id
        Resource resource = new Resource();
        resource.setUserId(userId);
        resource.setType(inferType(ext));
        resource.setOriginalName(original);
        resource.setStoragePath(storageName);
        resource.setUrl("/api/files/" + storageName);
        resource.setSize(file.getSize());
        resource.setMimeType(file.getContentType());
        resource.setMd5(md5);
        resource.setStatus(1);
        resourceMapper.insert(resource);
        return ResourceView.from(resource);
    }

    /**
     * 分页查询当前用户资源
     * @param type 类型过滤(可空:不过滤)
     * @param page 页码(从 1 开始)
     * @param limit 每页条数
     */
    public PageView<ResourceView> list(Long userId, Integer type, int page, int limit) {
        // 分页钳制:page 最小 1、limit 上限 100,对齐 Express,避免负 offset 触发 SQL 500
        page = Pagination.clampPage(page);
        limit = Pagination.clampLimit(limit);
        // 分页偏移:page 从 1 开始,offset = (page-1)*limit
        int offset = (page - 1) * limit;
        List<ResourceView> rows = resourceMapper.listByUser(userId, type, offset, limit)
                .stream().map(ResourceView::from).toList();
        long total = resourceMapper.countByUser(userId, type);
        return new PageView<>(rows, total, page);
    }

    /** 查询单个资源详情(公开,不存在则 404) */
    public ResourceView detail(Long id) {
        Resource r = resourceMapper.findById(id);
        // 未找到或已软删(status=0)一律视为不存在
        if (r == null) {
            throw new ApiException(404, "文件不存在");
        }
        return ResourceView.from(r);
    }

    /** 软删除资源(仅本人可删,越权 403) */
    public void delete(Long id, Long userId) {
        Resource r = resourceMapper.findById(id);
        if (r == null) {
            throw new ApiException(404, "文件不存在");
        }
        if (!r.getUserId().equals(userId)) {
            throw new ApiException(403, "无权删除此文件");
        }
        resourceMapper.softDelete(id);
    }

    /** 根据扩展名推断资源类型:1图 2视频 3文件 4音频 */
    private int inferType(String ext) {
        if (Set.of("jpg", "jpeg", "png", "gif", "webp").contains(ext)) return 1;
        if (Set.of("mp4", "webm").contains(ext)) return 2;
        if (Set.of("mp3").contains(ext)) return 4;
        return 3;
    }

    /** 计算文件 MD5 十六进制 */
    private String md5Hex(MultipartFile file) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(file.getBytes());
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new ApiException(500, "上传失败");
        }
    }
}
