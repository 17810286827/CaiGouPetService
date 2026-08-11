package caigou.caigoupetservice.controller;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * users 模块集成测试:搜索/详情/资料更新
 * 连真实 MySQL;测试用户统一以 testusr_ 为前缀,结束后按前缀批量清理,保证可重复运行
 */
@SpringBootTest
@AutoConfigureMockMvc
class UserApiIntegrationTest {

    /** 测试用户统一前缀:注册的用户名均以该前缀开头,便于统一清理 */
    private static final String PREFIX = "testusr_";
    /** JSON 读写工具,用于构造请求体与解析响应 */
    private static final ObjectMapper OM = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    /** 每个测试结束后清理本批注册的测试用户,避免相互污染 */
    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM users WHERE username LIKE '" + PREFIX + "%'");
    }

    /** 注册测试用户并返回 token(注册即登录,无需再走登录接口) */
    private String register(String username) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/auth/register").contentType("application/json")
                        .content(OM.writeValueAsString(Map.of("username", username, "password", "pass123", "nickname", "Nick" + username))))
                .andExpect(status().isCreated()).andReturn();
        return OM.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    /**
     * 搜索按昵称命中:注册两个用户后,用 q=Nick 搜索,
     * 应返回至少一条结果(排除自己后仍能匹配到另一用户)
     */
    @Test
    void search_byNickname_shouldReturnUsers() throws Exception {
        String token = register(PREFIX + "search1");
        register(PREFIX + "search2");
        mockMvc.perform(get("/api/users/search").param("q", "Nick").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users").isArray())
                .andExpect(jsonPath("$.users[0].username").isNotEmpty());
    }

    /**
     * 空关键字搜索:q 为空串时应返回空列表(契约:空 q 直接返回 {users:[]})
     */
    @Test
    void search_emptyQ_shouldReturnEmpty() throws Exception {
        String token = register(PREFIX + "search3");
        mockMvc.perform(get("/api/users/search").param("q", "").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users").isArray())
                .andExpect(jsonPath("$.users.length()").value(0));
    }

    /**
     * 详情-存在:公开接口返回完整用户资料,含社区计数(following_count 默认 0)
     */
    @Test
    void getById_existing_shouldReturnUser() throws Exception {
        register(PREFIX + "detail");
        Long uid = jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, PREFIX + "detail");
        mockMvc.perform(get("/api/users/" + uid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.username").value(PREFIX + "detail"))
                .andExpect(jsonPath("$.user.following_count").value(0));
    }

    /**
     * 详情-不存在:不存在的用户ID应返回 404 且错误信息为"用户不存在"
     */
    @Test
    void getById_missing_shouldReturn404() throws Exception {
        mockMvc.perform(get("/api/users/999999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("用户不存在"));
    }

    /**
     * 更新资料:传入新昵称与简介后,响应应回显更新后的新值(验证部分字段更新)
     */
    @Test
    void updateProfile_shouldReturnNewValues() throws Exception {
        String token = register(PREFIX + "prof");
        mockMvc.perform(put("/api/users/profile").header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(OM.writeValueAsString(Map.of("nickname", "新昵称", "bio", "你好世界"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.nickname").value("新昵称"))
                .andExpect(jsonPath("$.user.bio").value("你好世界"));
    }
}
