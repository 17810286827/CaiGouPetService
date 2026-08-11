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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.nullValue;

/**
 * 认证模块集成测试:连接真实 MySQL,验证迁移后各接口行为与 Express 端一致
 * 运行前提:MySQL 可达(caigopet 库存在),且通过环境变量 DB_PASS 提供密码
 * 测试用户名统一以 testauth_ 为前缀,结束后自动清理
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthApiIntegrationTest {

    private static final String BASE = "/api/auth";
    private static final String PASSWORD = "pass123";
    private static final String PREFIX = "testauth_";

    @Autowired
    private MockMvc mockMvc;
    // 手动创建 Jackson ObjectMapper:Spring Boot 4 默认 Jackson 3,不注册 com.fasterxml 的 ObjectMapper bean
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Autowired
    private JdbcTemplate jdbc;

    /** 每个测试结束后清理测试用户,保证可重复运行 */
    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM users WHERE username LIKE '" + PREFIX + "%'");
    }

    /** 注册一个测试用户并返回其 token(断言注册成功 201) */
    private String registerAndGetToken(String username) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("username", username, "password", PASSWORD));
        MvcResult result = mockMvc.perform(post(BASE + "/register")
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    /** 查询测试用户的主键 id(供 reset-password 等使用) */
    private long getUserId(String username) {
        Long id = jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
        return id == null ? -1L : id;
    }

    // ===== 注册 =====

    @Test
    void register_shouldReturn201WithToken() throws Exception {
        // 正常注册:返回 201 + token + 用户信息,avatar_url 为下划线字段
        mockMvc.perform(post(BASE + "/register")
                        .contentType("application/json")
                        .content("{\"username\":\"" + PREFIX + "reg\",\"password\":\"" + PASSWORD + "\",\"nickname\":\"Nick\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.username").value(PREFIX + "reg"))
                .andExpect(jsonPath("$.user.nickname").value("Nick"))
                .andExpect(jsonPath("$.user.avatar_url").value(nullValue()));
    }

    @Test
    void register_duplicateUsername_shouldReturn409() throws Exception {
        // 重复用户名注册:返回 409 用户名已存在
        registerAndGetToken(PREFIX + "dup");
        mockMvc.perform(post(BASE + "/register")
                        .contentType("application/json")
                        .content("{\"username\":\"" + PREFIX + "dup\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("用户名已存在"));
    }

    @Test
    void register_shortPassword_shouldReturn400() throws Exception {
        // 密码少于 6 位:返回 400
        mockMvc.perform(post(BASE + "/register")
                        .contentType("application/json")
                        .content("{\"username\":\"" + PREFIX + "short\",\"password\":\"123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("密码长度不能少于 6 位"));
    }

    // ===== 登录 =====

    @Test
    void login_success_shouldReturn200WithToken() throws Exception {
        // 正确密码登录:返回 200 + token
        registerAndGetToken(PREFIX + "login");
        mockMvc.perform(post(BASE + "/login")
                        .contentType("application/json")
                        .content("{\"username\":\"" + PREFIX + "login\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void login_wrongPassword_shouldReturn401() throws Exception {
        // 错误密码登录:返回 401 用户名或密码错误
        registerAndGetToken(PREFIX + "wrong");
        mockMvc.perform(post(BASE + "/login")
                        .contentType("application/json")
                        .content("{\"username\":\"" + PREFIX + "wrong\",\"password\":\"badpass\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("用户名或密码错误"));
    }

    // ===== 当前用户(me,验证拦截器鉴权) =====

    @Test
    void me_withToken_shouldReturn200() throws Exception {
        // 携带合法 token 访问 /me:返回当前用户信息
        String token = registerAndGetToken(PREFIX + "me");
        mockMvc.perform(get(BASE + "/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.username").value(PREFIX + "me"));
    }

    @Test
    void me_withoutToken_shouldReturn401() throws Exception {
        // 未携带 token:返回 401 未提供认证令牌
        mockMvc.perform(get(BASE + "/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未提供认证令牌"));
    }

    @Test
    void me_withInvalidToken_shouldReturn401() throws Exception {
        // 伪造 token:返回 401 无效的认证令牌
        mockMvc.perform(get(BASE + "/me").header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("无效的认证令牌"));
    }

    // ===== 修改密码 =====

    @Test
    void changePassword_shouldAllowLoginWithNewPassword() throws Exception {
        // 修改密码成功后,旧密码失效、新密码可登录
        String token = registerAndGetToken(PREFIX + "chg");
        String newPassword = "newpass456";
        mockMvc.perform(post(BASE + "/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"oldPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + newPassword + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("密码修改成功"));

        // 旧密码登录应失败
        mockMvc.perform(post(BASE + "/login").contentType("application/json")
                        .content("{\"username\":\"" + PREFIX + "chg\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
        // 新密码登录应成功
        mockMvc.perform(post(BASE + "/login").contentType("application/json")
                        .content("{\"username\":\"" + PREFIX + "chg\",\"password\":\"" + newPassword + "\"}"))
                .andExpect(status().isOk());
    }

    // ===== 扫码登录全流程 =====

    @Test
    void qrcodeLogin_fullFlow_shouldReturnSuccessToken() throws Exception {
        // 扫码登录闭环:init 生成会话 → confirm 确认 → poll 换取 token
        registerAndGetToken(PREFIX + "qr");

        MvcResult initResult = mockMvc.perform(get(BASE + "/qrcode/init"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session_token").isNotEmpty())
                .andExpect(jsonPath("$.qr_data_url").isNotEmpty())
                .andExpect(jsonPath("$.expires_in").value(120))
                .andReturn();
        String session = objectMapper.readTree(initResult.getResponse().getContentAsString())
                .get("session_token").asText();

        // 手机端确认
        mockMvc.perform(post(BASE + "/qrcode/confirm").contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "session", session, "username", PREFIX + "qr", "password", PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("确认成功，请在电脑端等待登录"));

        // 电脑端轮询:应返回 success + token + 用户
        mockMvc.perform(get(BASE + "/qrcode/poll").param("session", session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.username").value(PREFIX + "qr"));
    }

    @Test
    void qrcodePoll_expiredSession_shouldReturnExpired() throws Exception {
        // 不存在的会话轮询:返回 200 + status=expired(与 Express 一致,非 4xx)
        mockMvc.perform(get(BASE + "/qrcode/poll").param("session", "no-such-session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("expired"));
    }

    // ===== 找回密码 / 重置密码 =====

    @Test
    void forgotPassword_shouldReturnMessage() throws Exception {
        // 找回密码(模拟):返回成功提示
        registerAndGetToken(PREFIX + "forgot");
        mockMvc.perform(post(BASE + "/forgot-password").contentType("application/json")
                        .content("{\"account\":\"" + PREFIX + "forgot\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void resetPassword_invalidToken_shouldReturn400() throws Exception {
        // 先触发找回密码生成令牌,再用错误令牌重置:返回 400 重置令牌无效
        registerAndGetToken(PREFIX + "reset");
        mockMvc.perform(post(BASE + "/forgot-password").contentType("application/json")
                        .content("{\"account\":\"" + PREFIX + "reset\"}"))
                .andExpect(status().isOk());

        long uid = getUserId(PREFIX + "reset");
        mockMvc.perform(post(BASE + "/reset-password").contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "token", "deadbeef", "uid", uid, "newPassword", "abc123"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("重置令牌无效"));
    }
}
