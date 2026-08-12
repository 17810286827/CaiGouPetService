package caigou.caigoupetservice.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * pet 状态模块集成测试:获取(默认创建)/同步(upsert 覆盖 + 部分更新保留未传字段)
 * 运行前提:MySQL 可达,且通过环境变量 DB_PASS 提供密码
 * 测试用户名统一以 testpet_ 为前缀,结束后自动清理
 */
@SpringBootTest
@AutoConfigureMockMvc
class PetApiIntegrationTest {

    private static final String PREFIX = "testpet_";
    private static final ObjectMapper OM = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        // 先删子表(宠物状态/串门设置/消息/成员)再删主表(房间/用户);表无外键约束,顺序仅为语义清晰
        jdbc.update("DELETE FROM pet_states WHERE user_id IN " +
                "(SELECT id FROM users WHERE username LIKE '" + PREFIX + "%')");
        jdbc.update("DELETE FROM pet_visit_settings WHERE user_id IN " +
                "(SELECT id FROM users WHERE username LIKE '" + PREFIX + "%')");
        jdbc.update("DELETE FROM messages WHERE room_id IN " +
                "(SELECT room_id FROM chat_room_members WHERE user_id IN " +
                "(SELECT id FROM users WHERE username LIKE '" + PREFIX + "%'))");
        jdbc.update("DELETE FROM chat_room_members WHERE user_id IN " +
                "(SELECT id FROM users WHERE username LIKE '" + PREFIX + "%')");
        jdbc.update("DELETE FROM chat_rooms WHERE created_by IN " +
                "(SELECT id FROM users WHERE username LIKE '" + PREFIX + "%')");
        jdbc.update("DELETE FROM users WHERE username LIKE '" + PREFIX + "%'");
    }

    /** 注册用户并返回 token(注册即登录) */
    private String register(String username) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/auth/register").contentType("application/json")
                        .content(OM.writeValueAsString(Map.of("username", username, "password", "pass123"))))
                .andExpect(status().isCreated()).andReturn();
        return OM.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    /** 按用户名查询用户ID(注册成功后数据库已落行) */
    private Long userId(String username) {
        return jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
    }

    /** 同步宠物状态(公共步骤,不校验状态码,由调用方断言) */
    private MvcResult sync(String token, Map<String, Object> body) throws Exception {
        return mockMvc.perform(put("/api/pet/sync").header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(OM.writeValueAsString(body)))
                .andReturn();
    }

    /** A 创建与 otherId 的私聊房间(type=1,任务5已实现),供串门设置房间级用例建房间 */
    private MvcResult createPrivateRoom(String tokenA, Long otherId) throws Exception {
        return mockMvc.perform(post("/api/chat/rooms").header("Authorization", "Bearer " + tokenA)
                        .contentType("application/json")
                        .content(OM.writeValueAsString(Map.of("type", 1, "member_ids", List.of(otherId)))))
                .andReturn();
    }

    @Test
    void getState_shouldCreateDefault() throws Exception {
        // 新用户无状态:GET /api/pet 自动创建默认记录,emotion_state/personality 非 null 且为 {}
        String token = register(PREFIX + "g1");
        Long uid = userId(PREFIX + "g1");
        MvcResult r = mockMvc.perform(get("/api/pet").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode ps = OM.readTree(r.getResponse().getContentAsString()).get("pet_state");
        assertEquals(uid, ps.get("user_id").asLong(), "pet_state.user_id 应对应当前用户");
        assertTrue(ps.get("emotion_state").isObject(), "默认 emotion_state 应为 JSON 对象");
        assertTrue(ps.get("personality").isObject(), "默认 personality 应为 JSON 对象");
        assertEquals(0, ps.get("emotion_state").size(), "默认 emotion_state 应为空对象");
        assertEquals(0, ps.get("personality").size(), "默认 personality 应为空对象");
        // DB 侧也应落默认行(校验默认创建写库)
        Long cnt = jdbc.queryForObject("SELECT COUNT(*) FROM pet_states WHERE user_id = ?", Long.class, uid);
        assertEquals(1, cnt, "默认创建后 pet_states 应有一条记录");
    }

    @Test
    void getState_requiresLogin_shouldReturn401() throws Exception {
        // 全需登录:未带 token 访问 GET /api/pet 应 401
        mockMvc.perform(get("/api/pet"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void syncState_shouldUpsert() throws Exception {
        // 首次 sync 创建(emotion {joy:0.5});再次 sync 新值覆盖为 {joy:0.8}
        String token = register(PREFIX + "s1");
        MvcResult first = sync(token, Map.of("emotion_state", Map.of("joy", 0.5), "personality", Map.of()));
        assertEquals(200, first.getResponse().getStatus(), "首次同步应返回 200");
        JsonNode firstPs = OM.readTree(first.getResponse().getContentAsString()).get("pet_state");
        assertEquals(0.5, firstPs.get("emotion_state").get("joy").asDouble(), "首次同步 emotion.joy 应为 0.5");
        assertTrue(firstPs.get("personality").isObject() && firstPs.get("personality").size() == 0,
                "首次同步 personality 应为空对象");

        // 再次同步新值 → 覆盖而非追加
        MvcResult second = sync(token, Map.of("emotion_state", Map.of("joy", 0.8)));
        assertEquals(200, second.getResponse().getStatus(), "再次同步应返回 200");
        JsonNode secondPs = OM.readTree(second.getResponse().getContentAsString()).get("pet_state");
        assertEquals(0.8, secondPs.get("emotion_state").get("joy").asDouble(), "覆盖后 emotion.joy 应为 0.8");
    }

    @Test
    void syncState_onlyEmotion_shouldKeepPersonality() throws Exception {
        // 只传 emotion 不传 personality:先查后更,personality 保留旧值(规避全量 update 清空)
        String token = register(PREFIX + "s2");
        sync(token, Map.of("emotion_state", Map.of("joy", 0.5), "personality", Map.of("happy", 1)));

        MvcResult r = sync(token, Map.of("emotion_state", Map.of("joy", 0.9)));
        assertEquals(200, r.getResponse().getStatus(), "部分更新应返回 200");
        JsonNode ps = OM.readTree(r.getResponse().getContentAsString()).get("pet_state");
        assertEquals(0.9, ps.get("emotion_state").get("joy").asDouble(), "emotion.joy 应更新为 0.9");
        assertEquals(1, ps.get("personality").get("happy").asInt(), "只传 emotion 时 personality 应保留");
    }

    @Test
    void visitSettings_global_shouldReturnSettings() throws Exception {
        // 新用户默认全局允许:GET settings.global=true 且 rooms 为空;PUT {allow:false} → {global:false};GET 再查回落为 false
        String token = register(PREFIX + "g2");
        MvcResult initial = mockMvc.perform(get("/api/pet/visit-settings").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        JsonNode initSettings = OM.readTree(initial.getResponse().getContentAsString()).get("settings");
        assertEquals(true, initSettings.get("global").asBoolean(), "新用户默认全局允许应为 true");
        assertTrue(initSettings.get("rooms").isArray() && initSettings.get("rooms").size() == 0,
                "新用户房间覆盖列表应为空数组");

        MvcResult put = mockMvc.perform(put("/api/pet/visit-settings").header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(OM.writeValueAsString(Map.of("allow", false))))
                .andExpect(status().isOk()).andReturn();
        assertEquals(false, OM.readTree(put.getResponse().getContentAsString()).get("global").asBoolean(),
                "PUT 全局 allow=false 应返回 {global:false}");

        MvcResult after = mockMvc.perform(get("/api/pet/visit-settings").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        assertEquals(false, OM.readTree(after.getResponse().getContentAsString()).get("settings").get("global").asBoolean(),
                "PUT false 后 GET settings.global 应为 false");
    }

    @Test
    void visitSettings_room_shouldUpsertAndDelete() throws Exception {
        // A 建与 B 的私聊房间;B 对房间设置 allow=false → A 查对方 other_allow=false;B 删除覆盖(null) → A 再查回落 true
        String tokenA = register(PREFIX + "rA1");
        String tokenB = register(PREFIX + "rB1");
        Long bId = userId(PREFIX + "rB1");
        MvcResult created = createPrivateRoom(tokenA, bId);
        assertEquals(201, created.getResponse().getStatus(), "前置:创建私聊房间应 201");
        long roomId = OM.readTree(created.getResponse().getContentAsString()).get("room").get("id").asLong();

        // B 设置房间级覆盖 allow=false → {room_id, allow:false}
        MvcResult bPut = mockMvc.perform(put("/api/pet/visit-settings/room/" + roomId)
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType("application/json")
                        .content(OM.writeValueAsString(Map.of("allow", false))))
                .andExpect(status().isOk()).andReturn();
        JsonNode bResp = OM.readTree(bPut.getResponse().getContentAsString());
        assertEquals(roomId, bResp.get("room_id").asLong(), "房间覆盖响应应回显 room_id");
        assertEquals(false, bResp.get("allow").asBoolean(), "房间覆盖响应应回显 allow=false");

        // A 带 room_id 查对方(B)的有效允许状态 → other_allow=false(房间覆盖优先)
        MvcResult aGet = mockMvc.perform(get("/api/pet/visit-settings").param("room_id", String.valueOf(roomId))
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk()).andReturn();
        assertEquals(false, OM.readTree(aGet.getResponse().getContentAsString()).get("other_allow").asBoolean(),
                "B 设置房间覆盖 false 后,A 查对方 other_allow 应为 false");

        // B 删除房间覆盖(allow=null) → 回落全局(默认 true)
        Map<String, Object> deleteBody = new HashMap<>();
        deleteBody.put("allow", null);
        mockMvc.perform(put("/api/pet/visit-settings/room/" + roomId)
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType("application/json")
                        .content(OM.writeValueAsString(deleteBody)))
                .andExpect(status().isOk());
        MvcResult aGet2 = mockMvc.perform(get("/api/pet/visit-settings").param("room_id", String.valueOf(roomId))
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk()).andReturn();
        assertEquals(true, OM.readTree(aGet2.getResponse().getContentAsString()).get("other_allow").asBoolean(),
                "B 删除房间覆盖后,A 查对方 other_allow 应回落为 true");
    }

    @Test
    void visitSettings_room_notMember_should403() throws Exception {
        // C 非该房间成员对房间设置覆盖 → 403「不在该房间中」
        String tokenA = register(PREFIX + "nA1");
        register(PREFIX + "nB1");
        String tokenC = register(PREFIX + "nC1");
        Long bId = userId(PREFIX + "nB1");
        MvcResult created = createPrivateRoom(tokenA, bId);
        assertEquals(201, created.getResponse().getStatus(), "前置:创建私聊房间应 201");
        long roomId = OM.readTree(created.getResponse().getContentAsString()).get("room").get("id").asLong();

        MvcResult r = mockMvc.perform(put("/api/pet/visit-settings/room/" + roomId)
                        .header("Authorization", "Bearer " + tokenC)
                        .contentType("application/json")
                        .content(OM.writeValueAsString(Map.of("allow", false))))
                .andExpect(status().isForbidden()).andReturn();
        assertEquals("不在该房间中", OM.readTree(r.getResponse().getContentAsString()).get("error").asText(),
                "非成员设置房间覆盖应返回 403「不在该房间中」");
    }
}
