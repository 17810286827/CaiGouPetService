package caigou.caigoupetservice.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上传超限 413 集成测试:multipart.max-file-size 压到 1KB,向真实嵌入式容器上传 2KB 文件
 * 容器在进入 handler 前解析 multipart 即抛 MaxUploadSizeExceededException,
 * 由 GlobalExceptionHandler 转换为 413 "文件大小超出限制"
 * 注意:MockMvc 不经过真实容器解析,不会触发超限;必须用 RANDOM_PORT 真实服务器
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.servlet.multipart.max-file-size=1KB")
class ResourceUploadLimitIntegrationTest {

    /** 真实容器随机端口(Boot 4 的 TestRestTemplate 需额外模块,改用 spring-web 自带 RestTemplate 并拼接端口 URL) */
    @Value("${local.server.port}")
    private int port;

    /**
     * 超限上传:2KB 文件超出 1KB 上限,应返回 413 且错误信息为"文件大小超出限制"
     */
    @Test
    void oversizeUpload_shouldReturn413() {
        // 2KB 文件超出 1KB 上限;multipart 解析先于认证,无需携带 token
        byte[] bytes = new byte[2048];
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        // ByteArrayResource 需要覆写 getFilename,RestTemplate 才会按文件 part 序列化
        form.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "big.png";
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        // 4xx 响应会被 RestTemplate 转成 HttpClientErrorException,需捕获后检查状态码与响应体
        HttpStatusCode status;
        String responseBody;
        try {
            new RestTemplate().postForEntity("http://localhost:" + port + "/api/resources/upload",
                    new HttpEntity<>(form, headers), String.class);
            throw new AssertionError("超限请求应抛 HttpClientErrorException(413)");
        } catch (HttpClientErrorException e) {
            status = e.getStatusCode();
            responseBody = e.getResponseBodyAsString();
        }
        assertEquals(413, status.value(), "超限应返回 413");
        assertTrue(responseBody.contains("文件大小超出限制"), "响应应包含 文件大小超出限制,实际: " + responseBody);
    }
}
