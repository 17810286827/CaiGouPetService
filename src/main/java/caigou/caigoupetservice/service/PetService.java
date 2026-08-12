package caigou.caigoupetservice.service;

import caigou.caigoupetservice.entity.PetState;
import caigou.caigoupetservice.mapper.PetStateMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 宠物状态业务:获取状态(无则创建默认)/同步状态(upsert,部分字段更新)
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
            petStateMapper.insert(state);
            log.info("[pet] 无状态记录,默认创建 userId={}", userId);
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
            petStateMapper.insert(state);
            log.info("[pet] 首次同步,创建状态 userId={}", userId);
        } else {
            // 部分更新:先查现有记录,仅覆盖传入的非空字段,避免 update 全量覆盖清空未传字段
            String emotionJson = toJson(emotionRaw, null);
            if (emotionJson != null) {
                state.setEmotionState(emotionJson);
            }
            String personalityJson = toJson(personalityRaw, null);
            if (personalityJson != null) {
                state.setPersonality(personalityJson);
            }
            state.setLastSyncAt(now());
            petStateMapper.update(state);
            log.info("[pet] 同步状态 userId={}, 更新emotion={}, 更新personality={}",
                    userId, emotionJson != null, personalityJson != null);
        }
        // 回查 DB 维护的时间字段(created_at/updated_at),对齐 Express 响应
        return Map.of("pet_state", petStateView(petStateMapper.findByUserId(userId)));
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

    /**
     * 当前时间串(MySQL TIMESTAMP 可解析格式,与连接时区 Asia/Shanghai 一致)
     * @return yyyy-MM-dd HH:mm:ss 本地时间串
     */
    private String now() {
        return LocalDateTime.now().format(TS_FMT);
    }
}
