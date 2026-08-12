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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * posts 模块集成测试:创建/列表/详情(浏览量自增)/编辑/软删除/用户帖子
 */
@SpringBootTest
@AutoConfigureMockMvc
class PostApiIntegrationTest {

    private static final String PREFIX = "testpost_";
    private static final ObjectMapper OM = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private Long testUserId;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM likes WHERE user_id = ?", testUserId);
        jdbc.update("DELETE FROM favorites WHERE user_id = ?", testUserId);
        jdbc.update("DELETE FROM comments WHERE user_id = ?", testUserId);
        jdbc.update("DELETE FROM posts WHERE user_id = ?", testUserId);
        jdbc.update("DELETE FROM users WHERE id = ?", testUserId);
        // 清理其它测试用户(关注/被关注)
        jdbc.update("DELETE FROM users WHERE username LIKE '" + PREFIX + "%'");
    }

    private String register(String username) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/auth/register").contentType("application/json")
                        .content(OM.writeValueAsString(Map.of("username", username, "password", "pass123"))))
                .andExpect(status().isCreated()).andReturn();
        Long uid = jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
        testUserId = uid;
        return OM.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    private String createPost(String token, String content) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/posts").header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(OM.writeValueAsString(Map.of("title", "T", "content", content))))
                .andExpect(status().isCreated()).andReturn();
        return OM.readTree(r.getResponse().getContentAsString()).get("post").get("id").asText();
    }

    @Test
    void create_shouldReturn201WithPost() throws Exception {
        String token = register(PREFIX + "c1");
        mockMvc.perform(post("/api/posts").header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(OM.writeValueAsString(Map.of("title", "标题", "content", "正文内容"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.post.content").value("正文内容"))
                .andExpect(jsonPath("$.post.user.username").value(PREFIX + "c1"));
    }

    @Test
    void create_emptyContent_shouldReturn400() throws Exception {
        String token = register(PREFIX + "c2");
        mockMvc.perform(post("/api/posts").header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(OM.writeValueAsString(Map.of("title", "T", "content", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("内容不能为空"));
    }

    @Test
    void list_shouldReturnFeed() throws Exception {
        String token = register(PREFIX + "c3");
        createPost(token, "第一条");
        mockMvc.perform(get("/api/posts").param("page", "1").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posts").isArray())
                .andExpect(jsonPath("$.total").isNumber())
                .andExpect(jsonPath("$.page").value(1));
    }

    @Test
    void list_page0AndHugeLimit_shouldBeClamped() throws Exception {
        String token = register(PREFIX + "c7");
        createPost(token, "钳制测试");
        // page=0:service 钳制为 1,返回 200 而非负 offset 触发的 500,响应 page 应回显为 1
        mockMvc.perform(get("/api/posts").param("page", "0").param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posts").isArray())
                .andExpect(jsonPath("$.page").value(1));
        // limit=1000:service 钳制为 100,仍返回 200,不会按超大 limit 拉全表
        mockMvc.perform(get("/api/posts").param("page", "1").param("limit", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posts").isArray());
    }

    @Test
    void getById_shouldIncrementViewCount() throws Exception {
        String token = register(PREFIX + "c4");
        String pid = createPost(token, "浏览量");
        // 对齐 Express:响应返回自增后的浏览量(首次访问即为 1)
        mockMvc.perform(get("/api/posts/" + pid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.post.view_count").value(1));
        mockMvc.perform(get("/api/posts/" + pid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.post.view_count").value(2));
    }

    @Test
    void update_notOwner_shouldReturn403() throws Exception {
        String owner = register(PREFIX + "own");
        String pid = createPost(owner, "原文");
        String other = register(PREFIX + "oth");
        mockMvc.perform(put("/api/posts/" + pid).header("Authorization", "Bearer " + other)
                        .contentType("application/json")
                        .content(OM.writeValueAsString(Map.of("content", "篡改"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("无权编辑此帖子"));
    }

    @Test
    void delete_shouldSoftDelete() throws Exception {
        String token = register(PREFIX + "c5");
        String pid = createPost(token, "待删");
        mockMvc.perform(delete("/api/posts/" + pid).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("删除成功"));
        mockMvc.perform(get("/api/posts/" + pid))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("帖子不存在"));
    }

    @Test
    void userPosts_shouldReturnOwnPosts() throws Exception {
        String token = register(PREFIX + "c6");
        createPost(token, "我的帖子");
        Long uid = testUserId;
        mockMvc.perform(get("/api/users/" + uid + "/posts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posts[0].user.username").value(PREFIX + "c6"));
    }
}
