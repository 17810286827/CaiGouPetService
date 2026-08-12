package caigou.caigoupetservice.controller;

import caigou.caigoupetservice.annotation.PublicEndpoint;
import caigou.caigoupetservice.dto.PluginView;
import caigou.caigoupetservice.service.PluginService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 插件控制器:列表/分类/详情/下载(公开)与我的/上传/收藏/删除/收藏列表(需登录)
 * 本层仅做参数接收与结果返回,业务在 service 层
 * 认证说明:@PublicEndpoint 放行后拦截器不写 currentUserId,详情接口手动解析 Authorization 头计算 isFavorited
 * 路由顺序:GET /favorites/list、/my、/categories 等字面量路径须在 GET /{id} 之前注册,
 * Spring MVC 精确路径优先于路径变量,但显式在前更保险
 */
@RestController
@RequestMapping("/api/plugins")
@RequiredArgsConstructor
public class PluginController {

    private final PluginService pluginService;

    /** 插件列表(公开):分页+排序+分类/搜索过滤 */
    @PublicEndpoint
    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int limit,
                                    @RequestParam(defaultValue = "download_count") String sort,
                                    @RequestParam(defaultValue = "DESC") String order,
                                    @RequestParam(required = false) String category,
                                    @RequestParam(required = false) String search) {
        // 非法 sort/order/category 的兜底逻辑在 service 层白名单处理
        return pluginService.list(page, limit, sort, order, category, search);
    }

    /** 可用分类列表(公开) */
    @PublicEndpoint
    @GetMapping("/categories")
    public Map<String, List<String>> categories() {
        return Map.of("categories", pluginService.categories());
    }

    /** 我的插件(需登录,拦截器写入 currentUserId) */
    @GetMapping("/my")
    public Map<String, List<PluginView>> my(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("currentUserId");
        return Map.of("plugins", pluginService.listMy(userId));
    }

    /**
     * 我的收藏列表(需登录):返回 {favorites:[Plugin 含 author]}
     * 字面量路径,须在 GET /{id} 之前注册,避免被路径变量吞掉
     */
    @GetMapping("/favorites/list")
    public Map<String, List<PluginView>> favoritesList(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("currentUserId");
        return Map.of("favorites", pluginService.listFavorites(userId));
    }

    /**
     * 上传插件(需登录):multipart 字段 file(zip)
     * 200=同名更新 / 201=新建,状态码由 service 上传结果携带;file 设为 required=false,
     * 缺文件时交给 service 抛 400 业务文案,避免 Spring 先抛 MissingServletRequestPartException 兜底 500
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(HttpServletRequest request,
                                                      @RequestParam(value = "file", required = false) MultipartFile file) {
        Long userId = (Long) request.getAttribute("currentUserId");
        PluginService.UploadResult result = pluginService.upload(userId, file);
        return ResponseEntity.status(result.getStatus()).body(result.getBody());
    }

    /**
     * 下载插件(公开):下载数自增后以文件流返回 {name}-v{version}.zip
     * service 已校验插件存在/status=1/文件存在,这里仅组装 ResponseEntity<Resource>
     * Content-Disposition 用 RFC 5987 filename*(UTF-8 编码),插件名可能含中文
     */
    @PublicEndpoint
    @PostMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        PluginService.DownloadResult result = pluginService.download(id);
        FileSystemResource resource = new FileSystemResource(result.getFilePath());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(result.getFileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(resource);
    }

    /** 收藏/取消收藏(需登录):toggle 语义,返回 {favorited, favorite_count} */
    @PostMapping("/{id}/favorite")
    public Map<String, Object> favorite(@PathVariable Long id, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("currentUserId");
        return pluginService.toggleFavorite(userId, id);
    }

    /** 删除插件(需登录,仅作者):404 不存在 / 403 非作者,成功返回 {message:"Plugin deleted"} */
    @DeleteMapping("/{id}")
    public Map<String, String> delete(@PathVariable Long id, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("currentUserId");
        return pluginService.deletePlugin(userId, id);
    }

    /**
     * 插件详情(公开):404「Plugin not found」,响应含 isFavorited
     * @PublicEndpoint 放行后拦截器不写 currentUserId,这里把 Authorization 头交给 service 解析可选用户:
     * 有效 token 且用户正常 → 附 userId 计算收藏态;无头/无效 token/禁用用户 → null(未收藏)
     */
    @PublicEndpoint
    @GetMapping("/{id}")
    public Map<String, PluginView> detail(@PathVariable Long id,
                                          @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long userId = pluginService.resolveOptionalUserId(authorization);
        return Map.of("plugin", pluginService.detail(id, userId));
    }
}
