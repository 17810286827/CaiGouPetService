package caigou.caigoupetservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

    /**
     * 用 ZipOutputStream 内存构造 zip 字节数组(不落盘,供 MockMultipartFile 上传)
     * @param entries 文件名 → 内容 的映射,保持无目录结构(entry 名即根路径文件名)
     */
    private byte[] zipOf(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    /** 构造对齐 REQUIRED_MANIFEST_FIELDS 的合法 manifest.json 内容(entry 引用 index.html,zip 内须含该文件) */
    private String validManifest(String name, String version) {
        return "{\"id\":\"test-plugin\",\"name\":\"" + name + "\",\"version\":\"" + version
                + "\",\"description\":\"测试插件\",\"author\":\"tester\",\"entry\":\"index.html\"}";
    }

    @Test
    void upload_missingManifest_should400() throws Exception {
        String token = register(PREFIX + "up1");
        // zip 内只有 readme.txt,无 manifest.json → 400 固定文案
        byte[] zip = zipOf(Map.of("readme.txt", "hello".getBytes(StandardCharsets.UTF_8)));
        MockMultipartFile file = new MockMultipartFile("file", "p.zip", "application/zip", zip);
        mockMvc.perform(multipart("/api/plugins/upload").file(file).header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("manifest.json not found in plugin package"));
    }

    @Test
    void upload_blankManifest_should400() throws Exception {
        String token = register(PREFIX + "up4");
        // manifest.json 内容为纯空白:JSON.parse 抛错 → 400 "manifest.json is not valid JSON"
        // (Jackson readTree 对空白输入返回 MissingNode,需显式判定,否则会误落到 validator 校验失败分支)
        byte[] zip = zipOf(Map.of(
                "manifest.json", "   ".getBytes(StandardCharsets.UTF_8),
                "index.html", "<html></html>".getBytes(StandardCharsets.UTF_8)));
        MockMultipartFile file = new MockMultipartFile("file", "p.zip", "application/zip", zip);
        mockMvc.perform(multipart("/api/plugins/upload").file(file).header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("manifest.json is not valid JSON"));
    }

    @Test
    void upload_validZip_should201() throws Exception {
        String token = register(PREFIX + "up2");
        // 合法 manifest(对齐 REQUIRED_MANIFEST_FIELDS)+ entry 引用的 index.html 真实文件 → 201 新建
        String name = PREFIX + "up2plug";
        byte[] zip = zipOf(Map.of(
                "manifest.json", validManifest(name, "1.0.0").getBytes(StandardCharsets.UTF_8),
                "index.html", "<!DOCTYPE html><html><body>hi</body></html>".getBytes(StandardCharsets.UTF_8)));
        MockMultipartFile file = new MockMultipartFile("file", "p.zip", "application/zip", zip);
        mockMvc.perform(multipart("/api/plugins/upload").file(file).header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Plugin uploaded successfully"))
                .andExpect(jsonPath("$.plugin.name").value(name))
                .andExpect(jsonPath("$.plugin.version").value("1.0.0"))
                .andExpect(jsonPath("$.plugin.category").value("tool"))
                .andExpect(jsonPath("$.warnings").isArray());
    }

    @Test
    void upload_duplicateName_shouldUpdate() throws Exception {
        String token = register(PREFIX + "up3");
        String name = PREFIX + "up3plug";
        // 首次上传 v1.0.0 → 201 新建
        byte[] zipV1 = zipOf(Map.of(
                "manifest.json", validManifest(name, "1.0.0").getBytes(StandardCharsets.UTF_8),
                "index.html", "<html></html>".getBytes(StandardCharsets.UTF_8)));
        mockMvc.perform(multipart("/api/plugins/upload")
                        .file(new MockMultipartFile("file", "p.zip", "application/zip", zipV1))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated());
        // 同名同作者再传 v2.0.0 → 200 更新,版本号更新
        byte[] zipV2 = zipOf(Map.of(
                "manifest.json", validManifest(name, "2.0.0").getBytes(StandardCharsets.UTF_8),
                "index.html", "<html>v2</html>".getBytes(StandardCharsets.UTF_8)));
        mockMvc.perform(multipart("/api/plugins/upload")
                        .file(new MockMultipartFile("file", "p.zip", "application/zip", zipV2))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Plugin updated successfully"))
                .andExpect(jsonPath("$.plugin.version").value("2.0.0"));
        // DB 复核:同一 name+author 仅一行,version 为 2.0.0
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM plugins WHERE name = ? AND author_id = ?", Integer.class, name, testUserId);
        org.junit.jupiter.api.Assertions.assertEquals(1, rows, "同名上传应更新而非新增");
        String version = jdbc.queryForObject(
                "SELECT version FROM plugins WHERE name = ? AND author_id = ?", String.class, name, testUserId);
        org.junit.jupiter.api.Assertions.assertEquals("2.0.0", version, "版本应更新为 2.0.0");
    }

    @Test
    void download_shouldIncrementCountAndReturnFile() throws Exception {
        String token = register(PREFIX + "dl");
        String name = PREFIX + "dlplug";
        byte[] zip = zipOf(Map.of(
                "manifest.json", validManifest(name, "1.0.0").getBytes(StandardCharsets.UTF_8),
                "index.html", "<!DOCTYPE html><html><body>hi</body></html>".getBytes(StandardCharsets.UTF_8)));
        // 上传造带真实磁盘文件的插件,拿到 id 与 file_path
        MvcResult up = mockMvc.perform(multipart("/api/plugins/upload")
                        .file(new MockMultipartFile("file", "p.zip", "application/zip", zip))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        var pluginNode = OM.readTree(up.getResponse().getContentAsString()).path("plugin");
        long pluginId = pluginNode.path("id").asLong();
        String filePath = pluginNode.path("file_path").asText();

        // 下载接口公开:不带 token 调用,响应为 application/zip 文件流,Content-Disposition 携带 UTF-8 文件名
        MvcResult dl = mockMvc.perform(post("/api/plugins/" + pluginId + "/download"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/zip"))
                .andExpect(header().string("Content-Disposition", containsString(name + "-v1.0.0.zip")))
                .andReturn();
        // 响应字节应与上传的 zip 完全一致(磁盘文件流原样输出)
        assertArrayEquals(zip, dl.getResponse().getContentAsByteArray(), "下载内容应与上传 zip 一致");
        // DB 复核下载数自增为 1
        Integer count = jdbc.queryForObject("SELECT download_count FROM plugins WHERE id = ?", Integer.class, pluginId);
        assertEquals(1, count, "下载后 download_count 应为 1");
        // 清理磁盘测试文件(与 DB 清理解耦)
        Files.deleteIfExists(Paths.get(filePath));
    }

    @Test
    void favorite_toggle() throws Exception {
        String token = register(PREFIX + "fav");
        // 收藏者即 register 对应用户:显式捕获本地变量,DB 复核不依赖类级可变 testUserId
        Long favoriterId = testUserId;
        Long pluginId = createPlugin(testUserId, PREFIX + "fav_plug");

        // 首次收藏 → favorited:true 且收藏数 1
        mockMvc.perform(post("/api/plugins/" + pluginId + "/favorite").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.favorited").value(true))
                .andExpect(jsonPath("$.favorite_count").value(1));
        // 再收藏 → toggle 取消 → favorited:false 且收藏数归 0
        mockMvc.perform(post("/api/plugins/" + pluginId + "/favorite").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.favorited").value(false))
                .andExpect(jsonPath("$.favorite_count").value(0));
        // DB 复核:无残留收藏记录(按收藏者 id 查)
        Integer favRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM plugin_favorites WHERE user_id = ? AND plugin_id = ?", Integer.class, favoriterId, pluginId);
        assertEquals(0, favRows, "toggle 取消后不应有收藏记录");
    }

    @Test
    void delete_own_shouldSucceed() throws Exception {
        String token = register(PREFIX + "del");
        Long pluginId = createPlugin(testUserId, PREFIX + "del_plug");

        // 作者删除 → {message:"Plugin deleted"}
        mockMvc.perform(delete("/api/plugins/" + pluginId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Plugin deleted"));
        // 再查 → 404
        mockMvc.perform(get("/api/plugins/" + pluginId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Plugin not found"));
    }

    @Test
    void delete_notOwner_should403() throws Exception {
        register(PREFIX + "own");
        Long pluginId = createPlugin(testUserId, PREFIX + "own_plug");
        // 第二个用户(非作者)删除 → 403
        String otherToken = register(PREFIX + "oth");
        mockMvc.perform(delete("/api/plugins/" + pluginId).header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("You can only delete your own plugins"));
        // 插件仍存在
        mockMvc.perform(get("/api/plugins/" + pluginId))
                .andExpect(status().isOk());
    }

    @Test
    void favoritesList_shouldReturnMine() throws Exception {
        String token = register(PREFIX + "flist");
        Long pluginId = createPlugin(testUserId, PREFIX + "flist_plug");

        // 收藏后查询 → 列表含该插件,且结构镜像 Express:元素为包装对象,插件内嵌大写 Plugin 键(前端 f.Plugin 取数)
        mockMvc.perform(post("/api/plugins/" + pluginId + "/favorite").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/plugins/favorites/list").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.favorites").isArray())
                .andExpect(jsonPath("$.favorites[0].Plugin.name").value(PREFIX + "flist_plug"))
                .andExpect(jsonPath("$.favorites[0].Plugin.author.username").value(PREFIX + "flist"))
                .andExpect(jsonPath("$.favorites[0].user_id").value(testUserId))
                .andExpect(jsonPath("$.favorites[0].plugin_id").value(pluginId));
    }
}
