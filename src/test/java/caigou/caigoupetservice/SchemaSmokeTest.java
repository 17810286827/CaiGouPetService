package caigou.caigoupetservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 建表 smoke 测试:验证 13 张业务表在启动时已自动创建
 */
@SpringBootTest
class SchemaSmokeTest {

    private static final List<String> EXPECTED_TABLES = List.of(
            "posts", "comments", "likes", "favorites", "follows", "resources",
            "chat_rooms", "chat_room_members", "messages",
            "pet_states", "pet_visit_settings",
            "plugins", "plugin_favorites");

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void allBusinessTablesShouldExist() {
        for (String table : EXPECTED_TABLES) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
                    Integer.class, table);
            assertTrue(count != null && count > 0, "缺少数据表: " + table);
        }
    }
}
