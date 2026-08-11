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
 * comments 模块集成测试:根评论/回复树/参数校验/软删除
 */
@SpringBootTest
@AutoConfigureMockMvc
class CommentApiIntegrationTest {

    private static final String PREFIX = "testcmt_";
    private static final ObjectMapper OM = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM comments WHERE user_id IN (SELECT id FROM users WHERE username LIKE '" + PREFIX + "%')");
        jdbc.update("DELETE FROM posts WHERE user_id IN (SELECT id FROM users WHERE username LIKE '" + PREFIX + "%')");
        jdbc.update("DELETE FROM users WHERE username LIKE '" + PREFIX + "%'");
    }

    private String register(String username) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/auth/register").contentType("application/json")
                        .content(OM.writeValueAsString(Map.of("username", username, "password", "pass123"))))
                .andExpect(status().isCreated()).andReturn();
        return OM.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    private String createPost(String token, String content) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/posts").header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(OM.writeValueAsString(Map.of("content", content))))
                .andExpect(status().isCreated()).andReturn();
        return OM.readTree(r.getResponse().getContentAsString()).get("post").get("id").asText();
    }

    private String postComment(String token, long postId, String content, Long parentId) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("post_id", postId);
        body.put("content", content);
        if (parentId != null) body.put("parent_id", parentId);
        MvcResult r = mockMvc.perform(post("/api/comments").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content(OM.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn();
        return OM.readTree(r.getResponse().getContentAsString()).get("comment").get("id").asText();
    }

    @Test
    void commentRoot_thenReply_shouldBuildTree() throws Exception {
        String token = register(PREFIX + "c1");
        String pid = createPost(token, "评论帖");
        long postId = Long.parseLong(pid);
        String rootId = postComment(token, postId, "一级评论", null);
        String replyId = postComment(token, postId, "回复一级", Long.parseLong(rootId));
        mockMvc.perform(get("/api/comments/post/" + postId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comments[0].content").value("一级评论"))
                .andExpect(jsonPath("$.comments[0].replies[0].content").value("回复一级"));
    }

    @Test
    void comment_missingParams_shouldReturn400() throws Exception {
        String token = register(PREFIX + "c2");
        mockMvc.perform(post("/api/comments").header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(OM.writeValueAsString(Map.of("post_id", 1, "content", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("post_id 和 content 不能为空"));
    }

    @Test
    void comment_missingPost_shouldReturn404() throws Exception {
        String token = register(PREFIX + "c3");
        mockMvc.perform(post("/api/comments").header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(OM.writeValueAsString(Map.of("post_id", 999999, "content", "x"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("帖子不存在"));
    }

    @Test
    void delete_notOwner_shouldReturn403() throws Exception {
        String a = register(PREFIX + "c4");
        String pid = createPost(a, "评论帖2");
        String rootId = postComment(a, Long.parseLong(pid), "待删评论", null);
        String b = register(PREFIX + "c5");
        mockMvc.perform(delete("/api/comments/" + rootId).header("Authorization", "Bearer " + b))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("无权删除此评论"));
    }

    @Test
    void delete_shouldReduceCommentCount() throws Exception {
        String token = register(PREFIX + "c6");
        String pid = createPost(token, "评论帖3");
        String rootId = postComment(token, Long.parseLong(pid), "待删", null);
        Integer before = jdbc.queryForObject("SELECT comment_count FROM posts WHERE id = ?", Integer.class, Long.parseLong(pid));
        mockMvc.perform(delete("/api/comments/" + rootId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("删除成功"));
        Integer after = jdbc.queryForObject("SELECT comment_count FROM posts WHERE id = ?", Integer.class, Long.parseLong(pid));
        org.junit.jupiter.api.Assertions.assertEquals(before - 1, after, "删除评论后计数应减一");
    }

    @Test
    void deleteTwice_secondShould404AndKeepCount() throws Exception {
        String token = register(PREFIX + "c7");
        String pid = createPost(token, "评论帖4");
        String rootId = postComment(token, Long.parseLong(pid), "删两次", null);
        long postId = Long.parseLong(pid);
        Integer before = jdbc.queryForObject("SELECT comment_count FROM posts WHERE id = ?", Integer.class, postId);
        mockMvc.perform(delete("/api/comments/" + rootId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("删除成功"));
        Integer afterFirst = jdbc.queryForObject("SELECT comment_count FROM posts WHERE id = ?", Integer.class, postId);
        org.junit.jupiter.api.Assertions.assertEquals(before - 1, afterFirst, "首次删除后计数应减一");
        mockMvc.perform(delete("/api/comments/" + rootId).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("评论不存在"));
        Integer afterSecond = jdbc.queryForObject("SELECT comment_count FROM posts WHERE id = ?", Integer.class, postId);
        org.junit.jupiter.api.Assertions.assertEquals(afterFirst, afterSecond, "重复删除不应再次递减计数");
    }
}
