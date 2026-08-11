package caigou.caigoupetservice.controller;

import caigou.caigoupetservice.annotation.PublicEndpoint;
import caigou.caigoupetservice.dto.PageView;
import caigou.caigoupetservice.dto.ResourceView;
import caigou.caigoupetservice.service.ResourceService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 资源控制器:仅做参数接收与返回组装,业务在 service 层
 * 当前用户ID 由 JWT 拦截器写入 request attribute;公开只读接口加 @PublicEndpoint 放行
 */
@RestController
@RequestMapping("/api/resources")
@RequiredArgsConstructor
public class ResourceController {

    private final ResourceService resourceService;

    /**
     * 上传文件:成功 201 + {resource}
     * file 设为 required=false:缺文件时交给 service 抛 400 "请选择文件",
     * 否则 Spring 会先抛 MissingServletRequestPartException,无法命中业务文案
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, ResourceView>> upload(HttpServletRequest request,
                                                            @RequestParam(value = "file", required = false) MultipartFile file) {
        Long userId = (Long) request.getAttribute("currentUserId");
        return ResponseEntity.status(201).body(Map.of("resource", resourceService.upload(userId, file)));
    }

    /** 资源列表(需登录),支持 type 过滤与分页 */
    @GetMapping
    public Map<String, Object> list(HttpServletRequest request,
                                    @RequestParam(required = false) Integer type,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int limit) {
        Long userId = (Long) request.getAttribute("currentUserId");
        PageView<ResourceView> view = resourceService.list(userId, type, page, limit);
        // 返回结构与 Express 一致:{resources, total, page}
        return Map.of("resources", view.getRows(), "total", view.getTotal(), "page", view.getPage());
    }

    /** 资源详情(公开只读) */
    @PublicEndpoint
    @GetMapping("/{id}")
    public Map<String, ResourceView> detail(@PathVariable Long id) {
        return Map.of("resource", resourceService.detail(id));
    }

    /** 删除资源(本人),软删除 status=0 */
    @DeleteMapping("/{id}")
    public Map<String, String> delete(@PathVariable Long id, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("currentUserId");
        resourceService.delete(id, userId);
        // 成功删除返回固定文案,与 Express 一致
        return Map.of("message", "删除成功");
    }
}
