package cn.yhjyhj.weaver.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Weaver 的 Spring Boot 应用入口。 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class WeaverApplication {

    /** 创建应用入口；仅供框架和测试实例化。 */
    public WeaverApplication() {}

    /**
     * 启动 Weaver HTTP 服务。
     *
     * @param args Spring Boot 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(WeaverApplication.class, args);
    }
}
