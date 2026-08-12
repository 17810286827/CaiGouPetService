package caigou.caigoupetservice.controller;

import caigou.caigoupetservice.service.PetService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 宠物状态/串门设置控制器:获取状态(无则默认创建)/同步状态/串门设置(全局/房间覆盖)
 * 本层仅做参数接收与返回值处理,业务逻辑在 service 层
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

    /**
     * 获取串门设置:query room_id 可空;带 room_id 时校验成员并返回对方允许状态(other_allow)
     * @param roomId 聊天室ID(可空)
     * @param request 用于取当前登录用户ID
     * @return {settings:{global, rooms}[, other_allow]}
     */
    @GetMapping("/visit-settings")
    public Map<String, Object> getVisitSettings(@RequestParam(name = "room_id", required = false) Long roomId,
                                                HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("currentUserId");
        return petService.getVisitSettings(userId, roomId);
    }

    /**
     * 设置全局串门开关:allow 必须为 boolean(否则 400)
     * @param body 请求体{allow: boolean}
     * @param request 用于取当前登录用户ID
     * @return {global: boolean}
     */
    @PutMapping("/visit-settings")
    public Map<String, Object> setGlobal(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("currentUserId");
        return petService.setGlobal(userId, body.get("allow"));
    }

    /**
     * 设置房间级串门覆盖:先成员校验(403);allow 为 boolean 时 upsert,为 null 时删除覆盖
     * @param roomId 聊天室ID(路径)
     * @param body 请求体{allow: boolean|null}
     * @param request 用于取当前登录用户ID
     * @return {room_id, allow}
     */
    @PutMapping("/visit-settings/room/{roomId}")
    public Map<String, Object> setRoom(@PathVariable Long roomId,
                                       @RequestBody Map<String, Object> body,
                                       HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("currentUserId");
        return petService.setRoom(userId, roomId, body.get("allow"));
    }
}
