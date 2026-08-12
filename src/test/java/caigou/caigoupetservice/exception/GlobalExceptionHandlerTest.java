package caigou.caigoupetservice.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全局异常处理集成测试:请求不存在的接口(未迁移模块的路径)应返回 404 + {error},
 * 而非被兜底 Exception handler 当 500 处理并刷 ERROR 堆栈
 */
@SpringBootTest
@AutoConfigureMockMvc
class GlobalExceptionHandlerTest {

    private static final ObjectMapper OM = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    /**
     * 未迁移接口(/api/chat/rooms 等)打到 Java 时,应返回干净的 404 而非 500
     */
    @Test
    void nonexistentApi_shouldReturn404_not500() throws Exception {
        // 注册拿有效 token,绕过 JWT 拦截器进入 controller 分发
        String username = "smoke_nf_" + System.currentTimeMillis();
        MvcResult reg = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OM.writeValueAsString(Map.of(
                                "username", username, "password", "pass123456"))))
                .andExpect(status().isCreated())
                .andReturn();
        String token = OM.readTree(reg.getResponse().getContentAsString())
                .get("token").asText();

        // 请求一个不存在于后端的接口:Spring 找不到 controller 抛 NoResourceFoundException
        mockMvc.perform(get("/api/nonexistent")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())          // 修复前为 500
                .andExpect(jsonPath("$.error").isNotEmpty()); // 统一 JSON,非 HTML 错误页
    }
}
