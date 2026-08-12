package caigou.caigoupetservice.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * chat 房间模块集成测试:创建私聊(201)/私聊双向幂等复用(200,含反向)/当前用户房间列表
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChatApiIntegrationTest {

    private static final String PREFIX = "testchat_";
    private static final ObjectMapper OM = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        // 先删子表(消息/成员)再删主表(房间),最后删测试用户;表无外键约束,顺序仅为语义清晰
        jdbc.update("DELETE FROM messages WHERE room_id IN " +
                "(SELECT room_id FROM chat_room_members WHERE user_id IN " +
                "(SELECT id FROM users WHERE username LIKE '" + PREFIX + "%'))");
        jdbc.update("DELETE FROM chat_room_members WHERE user_id IN " +
                "(SELECT id FROM users WHERE username LIKE '" + PREFIX + "%')");
        jdbc.update("DELETE FROM chat_rooms WHERE created_by IN " +
                "(SELECT id FROM users WHERE username LIKE '" + PREFIX + "%')");
        jdbc.update("DELETE FROM users WHERE username LIKE '" + PREFIX + "%'");
    }

    /** 注册用户并返回 token(注册即登录) */
    private String register(String username) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/auth/register").contentType("application/json")
                        .content(OM.writeValueAsString(Map.of("username", username, "password", "pass123"))))
                .andExpect(status().isCreated()).andReturn();
        return OM.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    /** 按用户名查询用户ID(注册成功后数据库已落行) */
    private Long userId(String username) {
        return jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
    }

    /** A 创建与 otherId 的私聊房间(公共步骤,不校验状态码,由调用方断言) */
    private MvcResult createPrivateRoom(String tokenA, Long otherId) throws Exception {
        return mockMvc.perform(post("/api/chat/rooms").header("Authorization", "Bearer " + tokenA)
                        .contentType("application/json")
                        .content(OM.writeValueAsString(Map.of("type", 1, "member_ids", List.of(otherId)))))
                .andReturn();
    }

    @Test
    void createRoom_private_shouldReturn201() throws Exception {
        // 注册 A、B,B 为私聊对象
        String tokenA = register(PREFIX + "a1");
        register(PREFIX + "b1");
        Long bId = userId(PREFIX + "b1");
        MvcResult r = createPrivateRoom(tokenA, bId);
        // 新建私聊返回 201,响应含 room,type=1,且带 last_message 字段(无消息时为 null)
        assertEquals(201, r.getResponse().getStatus(), "新建私聊房间应返回 201");
        JsonNode room = OM.readTree(r.getResponse().getContentAsString()).get("room");
        assertEquals(1, room.get("type").asInt(), "新建私聊房间 type 应为 1");
        assertTrue(room.has("last_message"), "房间视图应含 last_message 字段(可空)");
    }

    @Test
    void createRoom_private_duplicate_shouldReuse() throws Exception {
        // 同 A、B 再次发起私聊:复用同一房间,返回 200 而非 201
        String tokenA = register(PREFIX + "a2");
        register(PREFIX + "b2");
        Long bId = userId(PREFIX + "b2");
        MvcResult first = createPrivateRoom(tokenA, bId);
        assertEquals(201, first.getResponse().getStatus(), "首次创建私聊应为 201");
        long roomId = OM.readTree(first.getResponse().getContentAsString()).get("room").get("id").asLong();
        MvcResult second = createPrivateRoom(tokenA, bId);
        assertEquals(200, second.getResponse().getStatus(), "重复创建私聊应返回 200 表示复用");
        long reusedRoomId = OM.readTree(second.getResponse().getContentAsString()).get("room").get("id").asLong();
        assertEquals(roomId, reusedRoomId, "重复创建私聊应复用同一房间 id");
    }

    @Test
    void createRoom_private_reverse_shouldReuse() throws Exception {
        // 反向幂等:A 创建与 B 的私聊后,B 用自己的 token 再发起与 A 的私聊,应复用同一房间(200 而非 201)
        String tokenA = register(PREFIX + "a4");
        String tokenB = register(PREFIX + "b4");
        Long aId = userId(PREFIX + "a4");
        Long bId = userId(PREFIX + "b4");
        MvcResult first = createPrivateRoom(tokenA, bId);
        assertEquals(201, first.getResponse().getStatus(), "A 首次创建私聊应为 201");
        long roomId = OM.readTree(first.getResponse().getContentAsString()).get("room").get("id").asLong();
        MvcResult reverse = createPrivateRoom(tokenB, aId);
        assertEquals(200, reverse.getResponse().getStatus(), "B 反向发起私聊应返回 200 表示复用");
        long reverseRoomId = OM.readTree(reverse.getResponse().getContentAsString()).get("room").get("id").asLong();
        assertEquals(roomId, reverseRoomId, "B 反向发起私聊应复用同一房间 id");
    }

    @Test
    void listRooms_shouldReturnRooms() throws Exception {
        // A 建一个与 B 的私聊房间后,GET /api/chat/rooms 应包含该房间
        String tokenA = register(PREFIX + "a3");
        register(PREFIX + "b3");
        Long bId = userId(PREFIX + "b3");
        MvcResult created = createPrivateRoom(tokenA, bId);
        assertEquals(201, created.getResponse().getStatus(), "前置:创建房间应成功");
        long roomId = OM.readTree(created.getResponse().getContentAsString()).get("room").get("id").asLong();

        MvcResult list = mockMvc.perform(get("/api/chat/rooms").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rooms").isArray())
                .andReturn();
        JsonNode rooms = OM.readTree(list.getResponse().getContentAsString()).get("rooms");
        boolean found = false;
        for (JsonNode room : rooms) {
            if (room.get("id").asLong() == roomId) {
                found = true;
                assertEquals(1, room.get("type").asInt(), "列表房间 type 应为 1");
                assertTrue(room.has("last_message"), "列表中的房间应含 last_message 字段(可空)");
            }
        }
        assertTrue(found, "当前用户房间列表应包含刚创建的房间");
    }
}
