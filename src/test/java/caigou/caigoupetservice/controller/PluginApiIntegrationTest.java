package caigou.caigoupetservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * plugins 模块集成测试:列表分页/非法排序回退/分类/详情 isFavorited/我的插件
 * 测试数据直接 jdbc 插入插件行(作者用注册用户),与上传接口解耦便于隔离
 */
@SpringBootTest
@AutoConfigureMockMvc
class PluginApiIntegrationTest {

    private static final String PREFIX = "testplg_";
    private static final ObjectMapper OM = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private Long testUserId;

    @AfterEach
    void cleanUp() {
        // 清理测试用户的收藏记录
        jdbc.update("DELETE FROM plugin_favorites WHERE user_id IN " +
                "(SELECT id FROM users WHERE username LIKE '" + PREFIX + "%')");
        // 清理测试用户插件上的收藏记录(收藏者可能非插件作者)
        jdbc.update("DELETE FROM plugin_favorites WHERE plugin_id IN " +
                "(SELECT id FROM plugins WHERE author_id IN (SELECT id FROM users WHERE username LIKE '" + PREFIX + "%'))");
        // 清理测试插件与测试用户
        jdbc.update("DELETE FROM plugins WHERE author_id IN " +
                "(SELECT id FROM users WHERE username LIKE '" + PREFIX + "%')");
        jdbc.update("DELETE FROM users WHERE username LIKE '" + PREFIX + "%'");
    }

    /** 注册测试用户并返回 token,同时记录 testUserId 供清理 */
    private String register(String username) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/auth/register").contentType("application/json")
                        .content(OM.writeValueAsString(Map.of("username", username, "password", "pass123"))))
                .andExpect(status().isCreated()).andReturn();
        testUserId = jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
        return OM.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    /** 直接 jdbc 插入一条已通过插件(status=1),返回插件 id */
    private Long createPlugin(Long authorId, String name) {
        jdbc.update("INSERT INTO plugins (name, version, description, author_id, category, tags, icon, manifest_json, file_path, file_size, status) " +
                        "VALUES (?, '1.0.0', ?, ?, 'tool', ?, NULL, NULL, NULL, ?, 1)",
                name, "测试插件描述", authorId, "tag1,tag2", 100);
        return jdbc.queryForObject("SELECT id FROM plugins WHERE name = ? AND author_id = ?", Long.class, name, authorId);
    }

    @Test
    void list_shouldReturnPagination() throws Exception {
        String username = PREFIX + "list";
        register(username);
        createPlugin(testUserId, username + "_plug");
        // 用 search 精确命中测试插件,避免与库中已有插件混排
        mockMvc.perform(get("/api/plugins").param("page", "1").param("limit", "10").param("search", username + "_plug"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plugins").isArray())
                .andExpect(jsonPath("$.plugins[0].name").value(username + "_plug"))
                .andExpect(jsonPath("$.plugins[0].author.username").value(username))
                .andExpect(jsonPath("$.pagination.page").value(1))
                .andExpect(jsonPath("$.pagination.limit").value(10))
                .andExpect(jsonPath("$.pagination.total").value(1))
                .andExpect(jsonPath("$.pagination.totalPages").value(1));
    }

    @Test
    void list_sortInvalid_shouldFallback() throws Exception {
        register(PREFIX + "sort");
        createPlugin(testUserId, PREFIX + "sort_plug");
        // sort 不在白名单 → 回退 download_count;order 非 ASC → DESC,均应 200 不报错
        mockMvc.perform(get("/api/plugins").param("sort", "bogus").param("order", "UP"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plugins").isArray())
                .andExpect(jsonPath("$.pagination.total").isNumber());
    }

    @Test
    void categories_shouldReturnList() throws Exception {
        MvcResult r = mockMvc.perform(get("/api/plugins/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories").isArray())
                .andReturn();
        // 白名单与 Express VALID_CATEGORIES 一致,至少包含 tool 且非空
        var categories = OM.readTree(r.getResponse().getContentAsString()).path("categories");
        assertTrue(categories.size() >= 1, "categories 应非空");
        assertTrue(categories.toString().contains("tool"), "categories 应包含 tool");
    }

    @Test
    void detail_shouldReturnIsFavorited() throws Exception {
        String token = register(PREFIX + "detail");
        Long pluginId = createPlugin(testUserId, PREFIX + "detail_plug");

        // 未收藏 → isFavorited=false
        mockMvc.perform(get("/api/plugins/" + pluginId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plugin.id").isNumber())
                .andExpect(jsonPath("$.plugin.isFavorited").isBoolean())
                .andExpect(jsonPath("$.plugin.isFavorited").value(false))
                .andExpect(jsonPath("$.plugin.author.id").value(testUserId));

        // jdbc 造收藏记录后 → isFavorited=true
        jdbc.update("INSERT INTO plugin_favorites (user_id, plugin_id) VALUES (?, ?)", testUserId, pluginId);
        mockMvc.perform(get("/api/plugins/" + pluginId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plugin.isFavorited").value(true));
    }

    @Test
    void detail_invalidToken_shouldBeNotFavorited() throws Exception {
        register(PREFIX + "itok");
        Long pluginId = createPlugin(testUserId, PREFIX + "itok_plug");
        // 用户已收藏,但携带无效 token → 按未收藏处理,仍返回 200
        jdbc.update("INSERT INTO plugin_favorites (user_id, plugin_id) VALUES (?, ?)", testUserId, pluginId);
        mockMvc.perform(get("/api/plugins/" + pluginId).header("Authorization", "Bearer invalid.token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plugin.isFavorited").value(false));
    }

    @Test
    void detail_disabledUserToken_shouldBeNotFavorited() throws Exception {
        String token = register(PREFIX + "dis");
        Long pluginId = createPlugin(testUserId, PREFIX + "dis_plug");
        // 已收藏,但用户被禁用后其有效 token 按未登录处理(与拦截器对禁用用户 401 语义一致)
        jdbc.update("INSERT INTO plugin_favorites (user_id, plugin_id) VALUES (?, ?)", testUserId, pluginId);
        jdbc.update("UPDATE users SET status = 0 WHERE id = ?", testUserId);
        mockMvc.perform(get("/api/plugins/" + pluginId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plugin.isFavorited").value(false));
    }

    @Test
    void detail_notFound_shouldReturn404() throws Exception {
        mockMvc.perform(get("/api/plugins/999999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Plugin not found"));
    }

    @Test
    void my_shouldReturnOwn() throws Exception {
        String token = register(PREFIX + "myp");
        createPlugin(testUserId, PREFIX + "mine");

        // 需登录:无 token → 401
        mockMvc.perform(get("/api/plugins/my"))
                .andExpect(status().isUnauthorized());

        // 带 token → 返回我的插件数组
        mockMvc.perform(get("/api/plugins/my").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plugins").isArray())
                .andExpect(jsonPath("$.plugins[0].name").value(PREFIX + "mine"));
    }
}
