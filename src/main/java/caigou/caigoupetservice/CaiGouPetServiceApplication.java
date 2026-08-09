package caigou.caigoupetservice;

import caigou.caigoupetservice.config.JwtProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * CaigoPetService 应用入口
 * 开启 MyBatis Mapper 扫描、定时任务(扫码会话清理)、JWT 配置绑定
 */
@SpringBootApplication
@MapperScan("caigou.caigoupetservice.mapper")
@EnableScheduling
@EnableConfigurationProperties(JwtProperties.class)
public class CaiGouPetServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CaiGouPetServiceApplication.class, args);
    }

}
