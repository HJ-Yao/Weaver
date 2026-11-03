package cn.yhjyhj.weaver.server.api;

import cn.yhjyhj.weaver.server.settings.ModelConfig;
import cn.yhjyhj.weaver.server.settings.ModelSettingsService;
import cn.yhjyhj.weaver.server.settings.ModelSettingsService.ActiveModelConflictException;
import cn.yhjyhj.weaver.server.settings.ModelSettingsService.CreateModelRequest;
import cn.yhjyhj.weaver.server.settings.ModelSettingsService.ModelNotFoundException;
import cn.yhjyhj.weaver.server.settings.ModelSettingsService.UpdateModelRequest;
import cn.yhjyhj.weaver.server.settings.SettingsView;
import jakarta.validation.Valid;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 暴露模型设置的脱敏 CRUD 与运行时激活 API。 */
@RestController
@RequestMapping("/api/settings/models")
public class SettingsController {

    /** 模型设置业务服务。 */
    private final ModelSettingsService settings;

    /**
     * 创建模型设置控制器。
     *
     * @param settings 模型设置服务
     */
    public SettingsController(ModelSettingsService settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /**
     * 返回当前激活项与全部模型配置的脱敏视图。
     *
     * @return 不包含 API 密钥的设置视图
     */
    @GetMapping
    public SettingsView list() {
        return settings.view();
    }

    /**
     * 新增模型配置，但不自动激活。
     *
     * @param request 已校验的新增请求
     * @return 201 与脱敏后的新增项
     */
    @PostMapping
    public ResponseEntity<SettingsView.ModelView> create(
            @Valid @RequestBody CreateModelRequest request) {
        ModelConfig created = settings.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(SettingsView.ModelView.fromSettings(created));
    }

    /**
     * 更新持久化模型配置。
     *
     * @param id 持久化模型 id
     * @param request 已校验的更新请求
     * @return 200 与脱敏后的配置
     */
    @PutMapping("/{id}")
    public SettingsView.ModelView update(
            @PathVariable("id") String id,
            @Valid @RequestBody UpdateModelRequest request) {
        return SettingsView.ModelView.fromSettings(settings.update(id, request));
    }

    /**
     * 激活持久化模型，或使用 {@code env} 回退到环境配置。
     *
     * @param id 持久化模型 id 或 {@code env}
     * @return 204 无响应体
     */
    @PostMapping("/{id}/activate")
    public ResponseEntity<Void> activate(@PathVariable("id") String id) {
        settings.activate(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 删除未激活的持久化模型配置。
     *
     * @param id 持久化模型 id
     * @return 204 无响应体
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") String id) {
        settings.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 将不存在的设置项映射为 404。
     *
     * @param exception 不存在异常
     * @return 无响应体 404
     */
    @ExceptionHandler(ModelNotFoundException.class)
    public ResponseEntity<Void> handleNotFound(ModelNotFoundException exception) {
        return ResponseEntity.notFound().build();
    }

    /**
     * 将删除激活项映射为 409。
     *
     * @param exception 激活冲突异常
     * @return 无响应体 409
     */
    @ExceptionHandler(ActiveModelConflictException.class)
    public ResponseEntity<Void> handleConflict(ActiveModelConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    /**
     * 将非法协议或地址映射为 400。
     *
     * @param exception 请求语义异常
     * @return 无响应体 400
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Void> handleBadRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().build();
    }
}
