package caigou.caigoupetservice.controller;

import caigou.caigoupetservice.service.PetService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 宠物状态控制器:获取状态(无则默认创建)/同步状态
 * 本层仅做当前用户透传与返回值处理,业务逻辑在 service 层
 * 认证说明:所有端点均需登录(拦截器校验,不加 @PublicEndpoint),对齐 Express pet.js authMiddleware
 */
@RestController
@RequestMapping("/api/pet")
@RequiredArgsConstructor
public class PetController {

    private final PetService petService;

    /**
     * 获取宠物状态:无记录则创建默认(emotion_state/personality 为 {})
     * @param request 用于取当前登录用户ID(JWT 拦截器写入 request attribute)
     * @return {pet_state: {...}}
     */
    @GetMapping
    public Map<String, Object> getState(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("currentUserId");
        return petService.getState(userId);
    }

    /**
     * 同步宠物状态:emotion_state/personality 原样 JSON 存取(不解析对象语义)
     * @param body    请求体(emotion_state/personality 可缺省,缺省保留旧值)
     * @param request 用于取当前登录用户ID
     * @return {pet_state: {...}}
     */
    @PutMapping("/sync")
    public Map<String, Object> syncState(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("currentUserId");
        return petService.syncState(userId, body.get("emotion_state"), body.get("personality"));
    }
}
