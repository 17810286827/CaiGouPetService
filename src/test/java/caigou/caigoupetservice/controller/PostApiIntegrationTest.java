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

    /** 创建草稿:仅标题,状态 0,返回帖子 ID */
    private String createDraft(String token, String title) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/posts").header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(OM.writeValueAsString(Map.of("title", title, "status", 0))))
                .andExpect(status().isCreated()).andReturn();
        return OM.readTree(r.getResponse().getContentAsString()).get("post").get("id").asText();
    }

    /** 直接插入关注关系(绕过接口,仅构造数据) */
    private void follow(Long followerId, Long targetId) {
        jdbc.update("INSERT INTO follows (user_id, follower_id) VALUES (?, ?)", targetId, followerId);
    }

    /** 按用户名查用户 ID(注册多用户时 testUserId 会被覆盖,统一用此 helper) */
    private Long testUserIdFor(String username) {
        return jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
    }

    /** 创建指定可见性的公开帖子,返回帖子 ID */
    private String createPostWithVisibility(String token, String content, int visibility) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/posts").header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(OM.writeValueAsString(Map.of(
                                "content", content,
                                "status", 1,
                                "visibility", visibility))))
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
    void createDraft_titleOnly_shouldReturn201WithStatus0() throws Exception {
        String token = register(PREFIX + "dr1");
        mockMvc.perform(post("/api/posts").header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(OM.writeValueAsString(Map.of(
                                "title", "草稿标题",
                                "status", 0,
                                "visibility", 2))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.post.status").value(0))
                .andExpect(jsonPath("$.post.visibility").value(2))
                .andExpect(jsonPath("$.post.title").value("草稿标题"));
    }

    @Test
    void createDraft_allEmpty_shouldReturn400() throws Exception {
        String token = register(PREFIX + "dr2");
        mockMvc.perform(post("/api/posts").header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(OM.writeValueAsString(Map.of("status", 0))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("草稿内容不能为空"));
    }

    @Test
    void drafts_shouldReturnOwnDraftsOnly() throws Exception {
        String token = register(PREFIX + "dr3");
        createPost(token, "已发布");
        mockMvc.perform(post("/api/posts").header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(OM.writeValueAsString(Map.of("title", "我的草稿", "status", 0))))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/posts/drafts").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posts.length()").value(1))
                .andExpect(jsonPath("$.posts[0].title").value("我的草稿"))
                .andExpect(jsonPath("$.posts[0].status").value(0));
    }

    @Test
    void updateDraft_publish_shouldReturnStatus1() throws Exception {
        String token = register(PREFIX + "dr4");
        String pid = createDraft(token, "草稿正文");
        mockMvc.perform(put("/api/posts/" + pid).header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(OM.writeValueAsString(Map.of("content", "正式内容", "status", 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.post.status").value(1))
                .andExpect(jsonPath("$.post.content").value("正式内容"));
    }

    @Test
    void create_titleTooLong_shouldReturn400() throws Exception {
        String token = register(PREFIX + "len");
        String title = "很".repeat(51);
        mockMvc.perform(post("/api/posts").header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(OM.writeValueAsString(Map.of("title", title, "content", "正文"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("标题不能超过 50 字"));
    }

    @Test
    void create_tooManyTags_shouldReturn400() throws Exception {
        String token = register(PREFIX + "tag");
        mockMvc.perform(post("/api/posts").header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(OM.writeValueAsString(Map.of(
                                "content", "正文",
                                "tags", java.util.List.of("a", "b", "c", "d")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("话题不能超过 3 个"));
    }

    @Test
    void create_invalidVisibility_shouldReturn400() throws Exception {
        String token = register(PREFIX + "vis");
        mockMvc.perform(post("/api/posts").header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(OM.writeValueAsString(Map.of("content", "正文", "visibility", 9))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("visibility 只能是 1-4"));
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

    @Test
    void visibility_anonymousOnlySeesPublic() throws Exception {
        String token = register(PREFIX + "v1");
        createPostWithVisibility(token, "公开帖", 1);
        createPostWithVisibility(token, "仅粉丝帖", 2);
        mockMvc.perform(get("/api/posts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posts[?(@.content=='公开帖')]").exists())
                .andExpect(jsonPath("$.posts[?(@.content=='仅粉丝帖')]").doesNotExist());
    }

    @Test
    void visibility_followerSeesFollowerPost() throws Exception {
        String author = register(PREFIX + "va");
        String follower = register(PREFIX + "vf");
        // register 返回 token,查 ID 需传注册用户名
        follow(testUserIdFor(PREFIX + "vf"), testUserIdFor(PREFIX + "va"));
        createPostWithVisibility(author, "粉丝可见", 2);
        mockMvc.perform(get("/api/posts").header("Authorization", "Bearer " + follower))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posts[?(@.content=='粉丝可见')]").exists());
    }

    @Test
    void visibility_friendSeesFriendPostButFollowerNot() throws Exception {
        String author = register(PREFIX + "fa");
        String friend = register(PREFIX + "ff");
        String follower = register(PREFIX + "fo");
        // register 返回 token,查 ID 需传注册用户名
        follow(testUserIdFor(PREFIX + "fo"), testUserIdFor(PREFIX + "fa"));
        follow(testUserIdFor(PREFIX + "fa"), testUserIdFor(PREFIX + "ff"));
        follow(testUserIdFor(PREFIX + "ff"), testUserIdFor(PREFIX + "fa"));
        createPostWithVisibility(author, "好友可见", 3);
        mockMvc.perform(get("/api/posts").header("Authorization", "Bearer " + friend))
                .andExpect(jsonPath("$.posts[?(@.content=='好友可见')]").exists());
        mockMvc.perform(get("/api/posts").header("Authorization", "Bearer " + follower))
                .andExpect(jsonPath("$.posts[?(@.content=='好友可见')]").doesNotExist());
    }

    @Test
    void visibility_authorSeesPrivatePost() throws Exception {
        String author = register(PREFIX + "pr");
        createPostWithVisibility(author, "仅自己", 4);
        mockMvc.perform(get("/api/posts").header("Authorization", "Bearer " + author))
                .andExpect(jsonPath("$.posts[?(@.content=='仅自己')]").exists());
        mockMvc.perform(get("/api/posts"))
                .andExpect(jsonPath("$.posts[?(@.content=='仅自己')]").doesNotExist());
    }
}
