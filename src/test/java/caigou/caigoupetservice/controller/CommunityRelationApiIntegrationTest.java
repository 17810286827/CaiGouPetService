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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 点赞/收藏/关注 模块集成测试:幂等/越权/计数/列表
 */
@SpringBootTest
@AutoConfigureMockMvc
class CommunityRelationApiIntegrationTest {

    private static final String PREFIX = "testrel_";
    private static final ObjectMapper OM = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        // 清理本类创建的测试用户及其关联数据
        jdbc.update("DELETE FROM likes WHERE user_id IN (SELECT id FROM users WHERE username LIKE '" + PREFIX + "%')");
        jdbc.update("DELETE FROM favorites WHERE user_id IN (SELECT id FROM users WHERE username LIKE '" + PREFIX + "%')");
        jdbc.update("DELETE FROM follows WHERE user_id IN (SELECT id FROM users WHERE username LIKE '" + PREFIX + "%') OR follower_id IN (SELECT id FROM users WHERE username LIKE '" + PREFIX + "%')");
        jdbc.update("DELETE FROM posts WHERE user_id IN (SELECT id FROM users WHERE username LIKE '" + PREFIX + "%')");
        jdbc.update("DELETE FROM users WHERE username LIKE '" + PREFIX + "%'");
    }

    private String register(String username) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/auth/register").contentType("application/json")
                        .content(OM.writeValueAsString(Map.of("username", username, "password", "pass123"))))
                .andExpect(status().isCreated()).andReturn();
        return OM.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    private long uid(String username) {
        return jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
    }

    private String createPost(String token, String content) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/posts").header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(OM.writeValueAsString(Map.of("content", content))))
                .andExpect(status().isCreated()).andReturn();
        return OM.readTree(r.getResponse().getContentAsString()).get("post").get("id").asText();
    }

    @Test
    void like_createAndRepeat_shouldBeIdempotent() throws Exception {
        String token = register(PREFIX + "a1");
        String pid = createPost(token, "被赞帖");
        mockMvc.perform(post("/api/likes/" + pid).header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.created").value(true));
        // 重复点赞:200 + created=false,计数不重复增加
        mockMvc.perform(post("/api/likes/" + pid).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(false));
        Integer likeCount = jdbc.queryForObject("SELECT like_count FROM posts WHERE id = ?", Integer.class, Long.parseLong(pid));
        org.junit.jupiter.api.Assertions.assertEquals(1, likeCount, "重复点赞计数不应增加");
    }

    @Test
    void like_missingPost_shouldReturn404() throws Exception {
        String token = register(PREFIX + "a2");
        mockMvc.perform(post("/api/likes/999999").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("帖子不存在"));
    }

    @Test
    void unlike_shouldReturnMessage() throws Exception {
        String token = register(PREFIX + "a3");
        String pid = createPost(token, "取消赞");
        mockMvc.perform(post("/api/likes/" + pid).header("Authorization", "Bearer " + token)).andExpect(status().isCreated());
        mockMvc.perform(delete("/api/likes/" + pid).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("取消点赞成功"));
        // 未点赞时取消失败
        mockMvc.perform(delete("/api/likes/" + pid).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("未点赞"));
    }

    @Test
    void likeList_shouldReturnPosts() throws Exception {
        String token = register(PREFIX + "a4");
        String pid = createPost(token, "列表里的帖子");
        mockMvc.perform(post("/api/likes/" + pid).header("Authorization", "Bearer " + token)).andExpect(status().isCreated());
        mockMvc.perform(get("/api/likes/user/" + uid(PREFIX + "a4")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posts[0].content").value("列表里的帖子"));
    }

    @Test
    void follow_self_shouldReturn400() throws Exception {
        String token = register(PREFIX + "b1");
        mockMvc.perform(post("/api/follow/" + uid(PREFIX + "b1")).header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("不能关注自己"));
    }

    @Test
    void follow_thenUnfollow_shouldWork() throws Exception {
        String a = register(PREFIX + "c1");
        register(PREFIX + "c2");
        mockMvc.perform(post("/api/follow/" + uid(PREFIX + "c2")).header("Authorization", "Bearer " + a))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.created").value(true));
        mockMvc.perform(delete("/api/follow/" + uid(PREFIX + "c2")).header("Authorization", "Bearer " + a))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("取消关注成功"));
    }

    @Test
    void followersList_shouldReturnFollower() throws Exception {
        String a = register(PREFIX + "d1");
        register(PREFIX + "d2");
        mockMvc.perform(post("/api/follow/" + uid(PREFIX + "d1")).header("Authorization", "Bearer " + register(PREFIX + "d3")))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/follow/" + uid(PREFIX + "d1") + "/followers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.followers[0].follower.username").isNotEmpty());
    }
}
