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
        // 先删宠物状态(子表语义)再删测试用户;表无外键约束,顺序仅为语义清晰
        jdbc.update("DELETE FROM pet_states WHERE user_id IN " +
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
}
