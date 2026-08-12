package caigou.caigoupetservice.service;

import caigou.caigoupetservice.entity.ChatRoomMember;
import caigou.caigoupetservice.entity.PetState;
import caigou.caigoupetservice.entity.PetVisitSetting;
import caigou.caigoupetservice.exception.ApiException;
import caigou.caigoupetservice.mapper.ChatRoomMemberMapper;
import caigou.caigoupetservice.mapper.PetStateMapper;
import caigou.caigoupetservice.mapper.PetVisitSettingMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 宠物状态/串门设置业务:获取状态(无则创建默认)/同步状态/串门设置(全局+房间覆盖+对方允许状态)
 * emotion_state/personality 以 JSON 字符串原样存取(不解析对象语义),
 * 响应时反序列化为 JSON 对象,对齐 Express pet.js 的 Sequelize JSON 列行为
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PetService {

    /** 空 JSON 对象:默认情绪/性格的兜底值,对齐 Express 创建时的 {} */
    private static final String EMPTY_JSON = "{}";
    /** DB TIMESTAMP 写入格式:MySQL 可直接解析,不用 ISO-8601 的 T/Z 分隔 */
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** JSON 序列化/反序列化:ObjectMapper 线程安全,静态复用 */
    private static final ObjectMapper OM = new ObjectMapper();

    /** 宠物状态数据访问 */
    private final PetStateMapper petStateMapper;
    /** 串门设置数据访问:全局/房间级覆盖的查改删 */
    private final PetVisitSettingMapper petVisitSettingMapper;
    /** 聊天室成员数据访问:校验是否房间成员并定位私聊对方 */
    private final ChatRoomMemberMapper chatRoomMemberMapper;

    /**
     * 获取宠物状态:无记录则创建默认(emotion_state/personality 均为 {})
     * @param userId 当前登录用户
     * @return {pet_state: {...}} 对齐 Express GET /api/pet
     */
    public Map<String, Object> getState(Long userId) {
        PetState state = petStateMapper.findByUserId(userId);
        if (state == null) {
            state = new PetState();
            state.setUserId(userId);
            state.setEmotionState(EMPTY_JSON);
            state.setPersonality(EMPTY_JSON);
            try {
                petStateMapper.insert(state);
                log.info("[pet] 无状态记录,默认创建 userId={}", userId);
            } catch (DuplicateKeyException e) {
                // 并发首访竞态:两请求同时查 null 后都 insert,后一个撞 uk_user 唯一键;
                // 幂等兜底——重查返回已存在行,避免 GlobalExceptionHandler 兜底 500(对齐 like/favorite 模式)
                state = petStateMapper.findByUserId(userId);
                log.info("[pet] 并发首访撞唯一键,复用已存在状态 userId={}", userId);
            }
        }
        // insert 只回填自增 id,回查补 DB 维护的 created_at/updated_at,对齐 Express 完整响应
        return Map.of("pet_state", petStateView(petStateMapper.findByUserId(userId)));
    }

    /**
     * 同步宠物状态:upsert 语义
     * 更新时先查到现有记录再赋值,仅覆盖传入字段,未传字段保留旧值
     * (规避 PetStateMapper.update 全量覆盖:只传 emotion 时不清空 personality)
     * @param userId         当前登录用户
     * @param emotionRaw     情绪对象(原始值,可为 Map/JSON 字符串,可空)
     * @param personalityRaw 性格对象(原始值,可为 Map/JSON 字符串,可空)
     * @return {pet_state: {...}} 对齐 Express PUT /api/pet/sync
     */
    public Map<String, Object> syncState(Long userId, Object emotionRaw, Object personalityRaw) {
        PetState state = petStateMapper.findByUserId(userId);
        if (state == null) {
            // 首次同步:新建记录,未传字段用 {} 兜底,与 Express create 分支一致
            state = new PetState();
            state.setUserId(userId);
            state.setEmotionState(toJson(emotionRaw, EMPTY_JSON));
            state.setPersonality(toJson(personalityRaw, EMPTY_JSON));
            state.setLastSyncAt(now());
            try {
                petStateMapper.insert(state);
                log.info("[pet] 首次同步,创建状态 userId={}", userId);
            } catch (DuplicateKeyException e) {
                // 并发首访竞态:撞 uk_user 唯一键,重查已存在行后按部分更新应用本次传入字段,
                // 避免 500 且保证本次同步值不丢失(幂等 upsert)
                state = petStateMapper.findByUserId(userId);
                applyPartialUpdate(state, emotionRaw, personalityRaw);
                state.setLastSyncAt(now());
                petStateMapper.update(state);
                log.info("[pet] 首次同步撞唯一键,复用并更新已有状态 userId={}", userId);
            }
        } else {
            // 部分更新:先查现有记录,仅覆盖传入的非空字段,避免 update 全量覆盖清空未传字段
            applyPartialUpdate(state, emotionRaw, personalityRaw);
            state.setLastSyncAt(now());
            petStateMapper.update(state);
            log.info("[pet] 同步状态 userId={}, 更新emotion={}, 更新personality={}",
                    userId, emotionRaw != null, personalityRaw != null);
        }
        // 回查 DB 维护的时间字段(created_at/updated_at),对齐 Express 响应
        return Map.of("pet_state", petStateView(petStateMapper.findByUserId(userId)));
    }

    /**
     * 部分更新宠物状态:仅覆盖传入的非空字段,未传字段保留旧值
     * (规避 PetStateMapper.update 全量覆盖:只传 emotion 时不清空 personality)
     * @param state         已从 DB 查出的现有记录(必非 null)
     * @param emotionRaw     情绪原始值(可为 Map/JSON 字符串/空白,空则保留旧值)
     * @param personalityRaw 性格原始值(可为 Map/JSON 字符串/空白,空则保留旧值)
     */
    private void applyPartialUpdate(PetState state, Object emotionRaw, Object personalityRaw) {
        String emotionJson = toJson(emotionRaw, null);
        if (emotionJson != null) {
            state.setEmotionState(emotionJson);
        }
        String personalityJson = toJson(personalityRaw, null);
        if (personalityJson != null) {
            state.setPersonality(personalityJson);
        }
    }

    /**
     * 将请求原始值规范化为 JSON 字符串存储
     * 原始值缺省(空或空白字符串)返回 defaultJson;已是字符串则原样存取,对象则序列化
     * @param raw         请求值(可为 Map/List/数值/字符串/null)
     * @param defaultJson 缺省时的兜底(insert 用 "{}",update 传 null 表示跳过该字段)
     * @return JSON 字符串;缺省且兜底为 null 时返回 null
     */
    private String toJson(Object raw, String defaultJson) {
        if (raw == null || (raw instanceof String s && s.isBlank())) {
            return defaultJson;
        }
        if (raw instanceof String s) {
            // 已是 JSON 字符串:原样存取,不解析对象语义
            return s;
        }
        try {
            return OM.writeValueAsString(raw);
        } catch (Exception e) {
            // 非法 JSON 值:记录日志并按缺省处理,避免脏数据入库
            log.warn("[pet] JSON 序列化失败,按缺省处理: raw={}", raw);
            return defaultJson;
        }
    }

    /**
     * 宠物状态响应视图:JSON 字符串字段反序列化为 JSON 对象(对齐 Express Sequelize JSON 列)
     * @param s 宠物状态实体(已含 DB 维护时间字段)
     * @return snake_case 下划线字段 Map
     */
    private Map<String, Object> petStateView(PetState s) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", s.getId());
        map.put("user_id", s.getUserId());
        map.put("emotion_state", parseJson(s.getEmotionState()));
        map.put("personality", parseJson(s.getPersonality()));
        map.put("last_sync_at", s.getLastSyncAt());
        map.put("created_at", s.getCreatedAt());
        map.put("updated_at", s.getUpdatedAt());
        return map;
    }

    /**
     * 反序列化 JSON 字符串为对象(供响应输出为 JSON 对象而非转义字符串)
     * @param json JSON 字符串(可空)
     * @return 反序列化对象;解析失败原样返回字符串并告警
     */
    private Object parseJson(String json) {
        if (json == null) {
            return null;
        }
        try {
            return OM.readValue(json, Object.class);
        } catch (Exception e) {
            log.warn("[pet] JSON 解析失败,原样返回字符串: {}", json);
            return json;
        }
    }

    // ===== 串门设置 =====

    /**
     * 获取串门设置:全局开关 + 房间覆盖列表
     * roomId 缺省仅返回本人设置;带 roomId 时先校验自己是房间成员(403「不在该房间中」),
     * 再查房间内另一成员的全局/房间设置,解析对方对本人的有效允许状态(other_allow,对齐 Express resolveAllow)
     * @param userId 当前登录用户
     * @param roomId 聊天室ID(可空,空=只看本人设置)
     * @return {settings:{global, rooms}[, other_allow]} 全局无记录时默认 true,对齐 Express global ? !!allow : true
     */
    public Map<String, Object> getVisitSettings(Long userId, Long roomId) {
        List<PetVisitSetting> rows = petVisitSettingMapper.listByUser(userId);
        Boolean global = null;
        List<Map<String, Object>> rooms = new ArrayList<>();
        for (PetVisitSetting s : rows) {
            if (s.getRoomId() == null) {
                // 全局设置(room_id IS NULL 的记录)
                global = toAllowValue(s);
            } else {
                Map<String, Object> rm = new LinkedHashMap<>();
                rm.put("room_id", s.getRoomId());
                rm.put("allow", toAllowValue(s));
                rooms.add(rm);
            }
        }
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("global", global == null ? Boolean.TRUE : global);
        settings.put("rooms", rooms);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("settings", settings);
        if (roomId != null) {
            // 带房间:校验自己是成员,再解析对方允许状态(对齐 Express ChatRoomMember 校验 + resolveAllow)
            requireRoomMember(roomId, userId);
            Long otherId = findOtherMember(roomId, userId);
            if (otherId != null) {
                Boolean otherGlobal = toAllowValue(petVisitSettingMapper.findGlobal(otherId));
                Boolean otherRoom = toAllowValue(petVisitSettingMapper.findRoom(otherId, roomId));
                result.put("other_allow", PetInteractionService.resolveAllow(otherGlobal, otherRoom));
            }
            log.info("[pet] 查询串门设置(带房间) userId={}, roomId={}, otherId={}", userId, roomId, otherId);
        } else {
            log.info("[pet] 查询串门设置(本人) userId={}, global={}, rooms={}", userId, global, rooms.size());
        }
        return result;
    }

    /**
     * 设置全局串门开关:upsert 语义(无记录新建,有记录覆盖)
     * @param userId 当前登录用户
     * @param allowRaw 请求原始值,必须为 Boolean(否则 400「allow 必须是布尔值」,对齐 Express typeof 校验)
     * @return {global: Boolean}
     */
    public Map<String, Object> setGlobal(Long userId, Object allowRaw) {
        if (!(allowRaw instanceof Boolean allow)) {
            throw new ApiException(400, "allow 必须是布尔值");
        }
        PetVisitSetting row = petVisitSettingMapper.findGlobal(userId);
        if (row == null) {
            PetVisitSetting s = new PetVisitSetting();
            s.setUserId(userId);
            s.setRoomId(null);
            s.setAllow(toDbAllow(allow));
            try {
                petVisitSettingMapper.insert(s);
            } catch (DuplicateKeyException e) {
                // 并发双请求同查 null 后都 insert,后一个撞 uk_user_room 唯一键;
                // 幂等兜底——重查已存在行并覆盖为本次 allow,避免 500 且保证本次值不丢失(对齐 getState/syncState 模式)
                row = petVisitSettingMapper.findGlobal(userId);
                row.setAllow(toDbAllow(allow));
                petVisitSettingMapper.update(row);
                log.info("[pet] 全局串门并发撞唯一键,复用并更新已有行 userId={}, allow={}", userId, allow);
            }
        } else {
            row.setAllow(toDbAllow(allow));
            petVisitSettingMapper.update(row);
        }
        log.info("[pet] 设置全局串门 userId={}, allow={}", userId, allow);
        return Map.of("global", allow);
    }

    /**
     * 设置房间级串门覆盖:成员校验 403「不在该房间中」;allow=null 删除覆盖(回落全局),否则 upsert
     * @param userId 当前登录用户
     * @param roomId 聊天室ID
     * @param allowRaw 请求原始值:null=删除覆盖,Boolean=设置覆盖(其他类型 400「allow 必须是布尔值或 null」)
     * @return {room_id, allow}
     */
    public Map<String, Object> setRoom(Long userId, Long roomId, Object allowRaw) {
        requireRoomMember(roomId, userId);
        Boolean allow;
        if (allowRaw == null) {
            allow = null;
        } else if (allowRaw instanceof Boolean b) {
            allow = b;
        } else {
            throw new ApiException(400, "allow 必须是布尔值或 null");
        }
        if (allow == null) {
            // 删除覆盖:删除后回落到全局设置
            petVisitSettingMapper.deleteRoom(userId, roomId);
        } else {
            PetVisitSetting row = petVisitSettingMapper.findRoom(userId, roomId);
            if (row == null) {
                PetVisitSetting s = new PetVisitSetting();
                s.setUserId(userId);
                s.setRoomId(roomId);
                s.setAllow(toDbAllow(allow));
                try {
                    petVisitSettingMapper.insert(s);
                } catch (DuplicateKeyException e) {
                    // 并发双请求同查 null 后都 insert,后一个撞 uk_user_room 唯一键;
                    // 幂等兜底——重查已存在行并覆盖为本次 allow,避免 500 且保证本次值不丢失(对齐 getState/syncState 模式)
                    row = petVisitSettingMapper.findRoom(userId, roomId);
                    row.setAllow(toDbAllow(allow));
                    petVisitSettingMapper.update(row);
                    log.info("[pet] 房间串门覆盖并发撞唯一键,复用并更新已有行 userId={}, roomId={}, allow={}",
                            userId, roomId, allow);
                }
            } else {
                row.setAllow(toDbAllow(allow));
                petVisitSettingMapper.update(row);
            }
        }
        log.info("[pet] 设置房间串门覆盖 userId={}, roomId={}, allow={}", userId, roomId, allow);
        // allow 可为 null(Map.of 拒绝 null 值),用 LinkedHashMap 承载删除分支的 null 回显
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("room_id", roomId);
        resp.put("allow", allow);
        return resp;
    }

    /**
     * 将串门设置记录归一化为 Boolean 允许值(DB 存 1/0)
     * @param s 串门设置记录(无记录时 null)
     * @return 允许值;无记录或字段为空返回 null(表示未设置,回落默认)
     */
    private Boolean toAllowValue(PetVisitSetting s) {
        if (s == null || s.getAllow() == null) {
            return null;
        }
        return s.getAllow() == 1;
    }

    /**
     * Boolean 允许值转 DB 存储值(1/0)
     * @param allow 允许值
     * @return 1=允许 0=拒绝
     */
    private int toDbAllow(Boolean allow) {
        return allow ? 1 : 0;
    }

    /**
     * 校验当前用户是否为房间成员,否则抛 403「不在该房间中」(对齐 Express ChatRoomMember 校验)
     * @param roomId 聊天室ID
     * @param userId 当前用户ID
     */
    private void requireRoomMember(Long roomId, Long userId) {
        if (chatRoomMemberMapper.find(roomId, userId) == null) {
            throw new ApiException(403, "不在该房间中");
        }
    }

    /**
     * 查找房间内另一成员(私聊两人,排除自己)
     * @param roomId 聊天室ID
     * @param userId 当前用户ID
     * @return 对方用户ID;房间无其他成员返回 null
     */
    private Long findOtherMember(Long roomId, Long userId) {
        return chatRoomMemberMapper.listByRoom(roomId).stream()
                .filter(m -> !m.getUserId().equals(userId))
                .map(ChatRoomMember::getUserId)
                .findFirst().orElse(null);
    }

    /**
     * 当前时间串(MySQL TIMESTAMP 可解析格式,与连接时区 Asia/Shanghai 一致)
     * @return yyyy-MM-dd HH:mm:ss 本地时间串
     */
    private String now() {
        return LocalDateTime.now().format(TS_FMT);
    }
}
