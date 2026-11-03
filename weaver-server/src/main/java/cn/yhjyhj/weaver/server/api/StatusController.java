package cn.yhjyhj.weaver.server.api;

import cn.yhjyhj.weaver.server.api.dto.ServerStatus;
import cn.yhjyhj.weaver.server.config.WeaverProperties;
import cn.yhjyhj.weaver.server.settings.ActiveModel;
import cn.yhjyhj.weaver.server.settings.ModelSettingsService;
import java.lang.management.ManagementFactory;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 暴露服务健康、存储和工作区运行信息。 */
@RestController
@RequestMapping("/api/status")
public class StatusController {

    private final WeaverProperties properties; // 提供工作区与单轮步数上限
    private final ModelSettingsService settings; // 当前激活模型设置的解析入口

    /**
     * 创建服务状态控制器。
     *
     * @param properties 已校验的 Weaver 运行配置
     * @param settings 模型设置服务
     */
    public StatusController(
            WeaverProperties properties, ModelSettingsService settings) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /**
     * 返回当前服务状态。
     *
     * @return 健康状态、SQLite 能力、工作区与模型配置以及 JVM 运行时长
     */
    @GetMapping
    public ServerStatus status() {
        ActiveModel activeModel = settings.activeModel();
        return new ServerStatus(
                "ok",
                "sqlite",
                true,
                properties.workspace().toAbsolutePath().normalize().toString(),
                properties.maxSteps(),
                ManagementFactory.getRuntimeMXBean().getUptime() / 1000,
                activeModel.model(),
                activeModel.protocol());
    }
}
