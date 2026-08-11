package caigou.caigoupetservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * resources 模块集成测试:上传/列表/详情/删除/越权/类型校验/MD5 去重
 * 连真实 MySQL;测试用户统一以 testres_ 为前缀,结束后按前缀批量清理,保证可重复运行
 */
@SpringBootTest
@AutoConfigureMockMvc
class ResourceApiIntegrationTest {

    private static final String PREFIX = "testres_";
    private static final ObjectMapper OM = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private Long testUserId;

    /**
     * 每个测试结束后按用户名前缀清理测试用户及其资源记录
     * 比"只清最近用户"更稳:delete_notOwner 会留下多个测试用户,统一按前缀关联清理
     */
    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM resources WHERE user_id IN (SELECT id FROM users WHERE username LIKE '" + PREFIX + "%')");
        jdbc.update("DELETE FROM users WHERE username LIKE '" + PREFIX + "%'");
    }

    /** 注册测试用户并返回 token(注册即登录) */
    private String register(String username) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/auth/register").contentType("application/json")
                        .content(OM.writeValueAsString(Map.of("username", username, "password", "pass123"))))
                .andExpect(status().isCreated()).andReturn();
        String token = OM.readTree(r.getResponse().getContentAsString()).get("token").asText();
        Long uid = jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
        testUserId = uid;
        return token;
    }

    @Test
    void upload_shouldReturn201WithResource() throws Exception {
        String token = register(PREFIX + "u1");
        MockMultipartFile file = new MockMultipartFile("file", "cat.png", "image/png", new byte[]{1, 2, 3});
        mockMvc.perform(multipart("/api/resources/upload").file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resource.url").value(org.hamcrest.Matchers.startsWith("/api/files/")))
                .andExpect(jsonPath("$.resource.type").value(1));
    }

    @Test
    void upload_unsupportedType_shouldReturn400() throws Exception {
        String token = register(PREFIX + "u2");
        MockMultipartFile file = new MockMultipartFile("file", "evil.exe", "application/octet-stream", new byte[]{1});
        mockMvc.perform(multipart("/api/resources/upload").file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("不支持的文件类型")));
    }

    @Test
    void upload_noFile_shouldReturn400() throws Exception {
        String token = register(PREFIX + "u3");
        mockMvc.perform(multipart("/api/resources/upload").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("请选择文件"));
    }

    @Test
    void upload_sameMd5_shouldDeduplicate() throws Exception {
        String token = register(PREFIX + "u4");
        byte[] bytes = {9, 9, 9};
        MockMultipartFile f1 = new MockMultipartFile("file", "a.png", "image/png", bytes);
        MockMultipartFile f2 = new MockMultipartFile("file", "b.png", "image/png", bytes);
        mockMvc.perform(multipart("/api/resources/upload").file(f1).header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated());
        // 相同 MD5 二次上传:返回旧记录(仍 201),资源行数不增加
        mockMvc.perform(multipart("/api/resources/upload").file(f2).header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated());
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM resources WHERE user_id = ?", Integer.class, testUserId);
        org.junit.jupiter.api.Assertions.assertEquals(1, count, "MD5 去重后不应新增记录");
    }

    @Test
    void delete_notOwner_shouldReturn403() throws Exception {
        String owner = register(PREFIX + "own");
        MockMultipartFile file = new MockMultipartFile("file", "c.png", "image/png", new byte[]{5});
        MvcResult up = mockMvc.perform(multipart("/api/resources/upload").file(file)
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isCreated()).andReturn();
        long rid = OM.readTree(up.getResponse().getContentAsString()).get("resource").get("id").asLong();

        String other = register(PREFIX + "oth"); // 复用 testUserId,覆盖 owner 关联清理
        jdbc.update("DELETE FROM resources WHERE user_id = ?", testUserId);
        testUserId = null; // 保留 owner 行由后续清理
        mockMvc.perform(delete("/api/resources/" + rid).header("Authorization", "Bearer " + other))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("无权删除此文件"));
    }

    @Test
    void delete_owner_shouldSoftDelete() throws Exception {
        String token = register(PREFIX + "del");
        MockMultipartFile file = new MockMultipartFile("file", "d.png", "image/png", new byte[]{7});
        MvcResult up = mockMvc.perform(multipart("/api/resources/upload").file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated()).andReturn();
        long rid = OM.readTree(up.getResponse().getContentAsString()).get("resource").get("id").asLong();
        mockMvc.perform(delete("/api/resources/" + rid).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("删除成功"));
        // 校验软删除真正生效:DB 中该资源 status 应为 0
        Integer status = jdbc.queryForObject("SELECT status FROM resources WHERE id = ?", Integer.class, rid);
        org.junit.jupiter.api.Assertions.assertEquals(0, status, "软删除后 resources.status 应为 0");
    }

    @Test
    void list_shouldReturnPagedAndTypeFiltered() throws Exception {
        String token = register(PREFIX + "lst");
        MockMultipartFile f = new MockMultipartFile("file", "x.png", "image/png", new byte[]{8});
        mockMvc.perform(multipart("/api/resources/upload").file(f).header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated());
        // 按类型 1(图片)过滤:应命中 1 条
        mockMvc.perform(get("/api/resources").header("Authorization", "Bearer " + token).param("type", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resources[0].type").value(1))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.page").value(1));
        // 按类型 2(视频)过滤:应为空
        mockMvc.perform(get("/api/resources").header("Authorization", "Bearer " + token).param("type", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void detail_shouldBePublicAndReturn404WhenMissing() throws Exception {
        // 公开接口:不带 token 也能访问
        mockMvc.perform(get("/api/resources/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("文件不存在"));
    }
}
