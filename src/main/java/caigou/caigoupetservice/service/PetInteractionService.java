package caigou.caigoupetservice.service;

import caigou.caigoupetservice.entity.ChatRoom;
import caigou.caigoupetservice.entity.ChatRoomMember;
import caigou.caigoupetservice.entity.Message;
import caigou.caigoupetservice.entity.PetVisitSetting;
import caigou.caigoupetservice.mapper.ChatRoomMapper;
import caigou.caigoupetservice.mapper.ChatRoomMemberMapper;
import caigou.caigoupetservice.mapper.MessageMapper;
import caigou.caigoupetservice.mapper.PetVisitSettingMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 宠物互动业务:串门/捣乱等 8 种动作的权限校验、30s 冷却与 msg_type=5 消息落库
 * 行为逐行对齐 Express server/src/services/pet-interaction.js:
 * 动作存在性 → 私聊房间 + 成员校验 → 接收者串门设置(resolveAllow) → 冷却检查 → 落消息
 * 失败分支返回结构化 code(UNKNOWN_ACTION/NOT_MEMBER/PERMISSION_DENIED/COOLDOWN),
 * 成功返回 ack 数据(ok=true + message + action + cooldownUntil),由 socket 层转发
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PetInteractionService {

    /** 8 种宠物互动动作定义(id+中文文案),与前端 PET_ACTIONS 常量逐一对应 */
    private static final List<Map<String, String>> PET_ACTIONS = List.of(
            Map.of("id", "visit", "label", "串门"),
            Map.of("id", "mischief", "label", "捣乱"),
            Map.of("id", "high_five", "label", "击掌"),
            Map.of("id", "dance", "label", "跳舞"),
            Map.of("id", "gift", "label", "送花"),
            Map.of("id", "fight", "label", "打架"),
            Map.of("id", "cuddle", "label", "蹭蹭"),
            Map.of("id", "kiss", "label", "亲亲")
    );

    /** 互动冷却时长:30 秒,对齐 Express PET_INTERACT_COOLDOWN_MS */
    private static final long PET_INTERACT_COOLDOWN_MS = 30 * 1000L;

    /** JSON 解析/序列化:msg_type=5 的 content 为 JSON 字符串;静态实例可多线程安全复用 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 聊天室查询:校验房间存在且为私聊(type=1) */
    private final ChatRoomMapper chatRoomMapper;
    /** 聊天室成员查询:校验发送者为成员并定位接收者 */
    private final ChatRoomMemberMapper chatRoomMemberMapper;
    /** 消息数据访问:落 msg_type=5 消息与冷却时间回查 */
    private final MessageMapper messageMapper;
    /** 串门设置查询:按接收者的全局/房间设置判定是否允许 */
    private final PetVisitSettingMapper petVisitSettingMapper;

    /**
     * 返回 8 种宠物互动动作清单(供前端展示/动作合法性校验)
     * @return 动作 id+label 的不可变列表
     */
    public List<Map<String, String>> petActions() {
        return PET_ACTIONS;
    }

    /**
     * 解析串门允许结果:房间级覆盖优先,其次全局设置(默认允许)
     * 对齐 Express resolveAllow:roomOverride 非空直接取其值,否则 global !== false
     * static 供 PetService 复用(串门设置查询对方允许状态与互动动作共用同一规则)
     * @param global 全局允许(未设置时 null)
     * @param roomOverride 房间级覆盖(未设置时 null)
     * @return true=允许串门
     */
    public static boolean resolveAllow(Boolean global, Boolean roomOverride) {
        if (roomOverride != null) {
            return roomOverride;
        }
        // 全局无记录(null)时默认允许;仅显式 false 才拒绝
        return global == null || global;
    }

    /**
     * 处理宠物互动请求:串门权限校验 → 冷却检查 → 落 msg_type=5 消息
     * @param roomId 聊天室ID(须为私聊)
     * @param senderId 发起互动用户ID
     * @param actionId 动作 id(如 visit)
     * @param clientEventId 客户端事件 id(幂等去重,映射 client_msg_id)
     * @return 失败分支 {ok:false, code, message[, retryAfterMs]};
     *         成功分支 {ok:true, message, action, cooldownUntil, duplicate?}
     */
    public Map<String, Object> handlePetInteract(Long roomId, Long senderId, String actionId, String clientEventId) {
        Map<String, Object> result = new HashMap<>();
        result.put("ok", false);
        // 1. 动作合法性:actionId 必须存在,否则 UNKNOWN_ACTION(对齐 Express getAction)
        Map<String, String> action = getAction(actionId);
        if (action == null) {
            result.put("code", "UNKNOWN_ACTION");
            result.put("message", "未知动作");
            return result;
        }
        // 2. 房间校验:必须存在且为私聊(type=1),否则 NOT_MEMBER
        ChatRoom room = chatRoomMapper.findById(roomId);
        if (room == null || room.getType() == null || room.getType() != 1) {
            result.put("code", "NOT_MEMBER");
            result.put("message", "不在私聊房间中");
            return result;
        }
        // 3. 成员校验:发送者必须是房间成员;私聊需恰好两位成员,另一位为接收者
        List<ChatRoomMember> members = chatRoomMemberMapper.listByRoom(roomId);
        boolean senderIsMember = members.stream().anyMatch(m -> m.getUserId().equals(senderId));
        if (!senderIsMember) {
            result.put("code", "NOT_MEMBER");
            result.put("message", "不在私聊房间中");
            return result;
        }
        ChatRoomMember receiver = members.stream()
                .filter(m -> !m.getUserId().equals(senderId))
                .findFirst().orElse(null);
        if (receiver == null) {
            result.put("code", "NOT_MEMBER");
            result.put("message", "私聊需要两位成员");
            return result;
        }
        // 4. 幂等去重:同一 client_msg_id 重复提交返回已存在消息(对齐 Express duplicate 语义)
        Message existing = messageMapper.findByClientMsgId(roomId, senderId, clientEventId);
        if (existing != null) {
            result.put("ok", true);
            result.put("message", existing);
            result.put("action", action);
            result.put("duplicate", true);
            result.put("cooldownUntil", cooldownUntil());
            return result;
        }
        // 5. 串门允许:取接收者的全局与房间设置,房间覆盖优先,其次全局(默认允许)
        Boolean globalAllow = resolveAllowValue(petVisitSettingMapper.findGlobal(receiver.getUserId()));
        Boolean roomAllow = resolveAllowValue(petVisitSettingMapper.findRoom(receiver.getUserId(), roomId));
        if (!resolveAllow(globalAllow, roomAllow)) {
            result.put("code", "PERMISSION_DENIED");
            result.put("message", "对方关闭了串门");
            return result;
        }
        // 6. 冷却检查:基于该发送者最近有效互动(status=1)的 created_at(epoch 毫秒,DB 侧换算),30s 内拒绝
        Long lastEventAt = lastPetInteractAt(roomId, senderId);
        Map<String, Object> cooldown = checkCooldown(lastEventAt, System.currentTimeMillis());
        if (!Boolean.TRUE.equals(cooldown.get("ok"))) {
            result.put("code", "COOLDOWN");
            result.put("message", "冷却中");
            result.put("retryAfterMs", cooldown.get("retryAfterMs"));
            return result;
        }
        // 7. 落 msg_type=5 消息:status 显式置 1(任务4审查:insert 显式列 status,null 违反 NOT NULL)
        Message msg = new Message();
        msg.setRoomId(roomId);
        msg.setSenderId(senderId);
        msg.setMsgType(5);
        msg.setContent(buildContent(action, senderId, clientEventId));
        msg.setStatus(1);
        msg.setClientMsgId(clientEventId);
        try {
            messageMapper.insert(msg);
        } catch (DuplicateKeyException e) {
            // 唯一键 uk_sender_client(sender_id+client_msg_id)冲突:同房间可查到说明是并发双写竞态,
            // 按幂等成功返回;查不到说明是跨房间/跨场景复用了 client_event_id(唯一键不含 room_id),
            // 属客户端错误——消息未落库,必须返回非 ok,避免"假成功"(广播 message=null 且不触发冷却)
            log.warn("[pet:interact] client_msg_id 唯一键冲突: room={}, senderId={}, eventId={}",
                    roomId, senderId, clientEventId);
            Message concurrent = messageMapper.findByClientMsgId(roomId, senderId, clientEventId);
            if (concurrent == null) {
                result.put("code", "DUPLICATE");
                result.put("message", "事件 ID 重复,请更换 client_event_id");
                return result;
            }
            result.put("ok", true);
            result.put("message", concurrent);
            result.put("action", action);
            result.put("duplicate", true);
            result.put("cooldownUntil", cooldownUntil());
            return result;
        }
        // 回查完整消息(含 DB 维护的 id/created_at),供 socket 广播使用
        Message full = messageMapper.findById(msg.getId());
        result.put("ok", true);
        result.put("message", full);
        result.put("action", action);
        result.put("cooldownUntil", cooldownUntil());
        log.info("[pet:interact] 互动成功落库: room={}, senderId={}, action={}, msgId={}",
                roomId, senderId, actionId, msg.getId());
        return result;
    }

    /**
     * 按动作 id 查找动作定义
     * @param actionId 动作 id(如 visit)
     * @return 动作 id+label 的 Map,不存在返回 null
     */
    private Map<String, String> getAction(String actionId) {
        for (Map<String, String> a : PET_ACTIONS) {
            if (a.get("id").equals(actionId)) {
                return a;
            }
        }
        return null;
    }

    /**
     * 将串门设置记录归一化为 Boolean 允许值
     * @param setting 串门设置记录(无记录时 null)
     * @return 允许值;无记录返回 null(表示回落默认允许)
     */
    private Boolean resolveAllowValue(PetVisitSetting setting) {
        if (setting == null || setting.getAllow() == null) {
            return null;
        }
        // DB 存 1/0,归一化为 true/false
        return setting.getAllow() == 1;
    }

    /**
     * 查找该用户在该房间最近一次有效宠物互动时间(epoch 毫秒)
     * 直接由 SQL 以 UNIX_TIMESTAMP 在 DB 侧换算,规避 JDBC 时间串与 JVM 时区不一致导致的冷却误判
     * (任务4审查:仅统计 status=1 的有效消息;content LIKE 过滤识别 pet_interact 系统消息)
     * @param roomId 聊天室ID
     * @param senderId 发起用户ID
     * @return 最近互动时间毫秒;无有效互动返回 null
     */
    private Long lastPetInteractAt(Long roomId, Long senderId) {
        return messageMapper.findLastPetInteractTime(roomId, senderId);
    }

    /**
     * 冷却判定:距上次互动不足 30s 返回 retryAfterMs
     * @param lastEventAt 上次互动 epoch 毫秒(无则 null)
     * @param nowMs 当前 epoch 毫秒
     * @return {ok:true} 放行;{ok:false, retryAfterMs} 冷却中
     */
    private Map<String, Object> checkCooldown(Long lastEventAt, long nowMs) {
        if (lastEventAt == null) {
            return Map.of("ok", Boolean.TRUE);
        }
        long elapsed = nowMs - lastEventAt;
        if (elapsed >= PET_INTERACT_COOLDOWN_MS) {
            return Map.of("ok", Boolean.TRUE);
        }
        // 剩余冷却时间回传,供 reject 的 retry_after_ms 字段使用
        return Map.of("ok", Boolean.FALSE, "retryAfterMs", PET_INTERACT_COOLDOWN_MS - elapsed);
    }

    /**
     * 构造 msg_type=5 消息的 content JSON
     * 对齐 Express:kind=pet_interact + action_id/action_label + from;
     * 另含 event_id(契约要求,便于客户端回执对应)
     * @param action 动作定义
     * @param senderId 发起用户ID
     * @param clientEventId 客户端事件 id
     * @return JSON 字符串
     */
    private String buildContent(Map<String, String> action, Long senderId, String clientEventId) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("kind", "pet_interact");
        content.put("action_id", action.get("id"));
        content.put("action_label", action.get("label"));
        content.put("event_id", clientEventId);
        content.put("from", Map.of("id", senderId));
        try {
            return OBJECT_MAPPER.writeValueAsString(content);
        } catch (Exception e) {
            // content 为内部固定结构,序列化失败属编码级异常,抛出由 socket 层兜底 SERVER_ERROR
            log.error("[pet:interact] content JSON 序列化失败", e);
            throw new IllegalStateException("content JSON 序列化失败");
        }
    }

    /**
     * 计算本次互动后的冷却截止时间(ISO-8601 UTC,对齐 Express toISOString)
     * @return 当前时刻 + 30s 的 ISO 时间串
     */
    private String cooldownUntil() {
        return Instant.now().plusMillis(PET_INTERACT_COOLDOWN_MS).toString();
    }
}
