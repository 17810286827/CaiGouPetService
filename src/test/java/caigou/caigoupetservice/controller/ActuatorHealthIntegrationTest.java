package caigou.caigoupetservice.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Actuator 监控端点集成测试:替代已下线的 Express admin 面板
 * 验证 /actuator/health 返回 UP(含 MySQL 连通)、/actuator/info 可访问
 * 运行前提:MySQL 可达(caigoupet 库),密码通过环境变量 DB_PASS 提供
 */
@SpringBootTest
@AutoConfigureMockMvc
class ActuatorHealthIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    /** 健康检查应返回 200 且顶层 status=UP */
    @Test
    void health_shouldReturnUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    /** info 端点应可访问(200),当前默认返回空对象 */
    @Test
    void info_shouldReturn200() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk());
    }
}
