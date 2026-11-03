package cn.yhjyhj.weaver.server.settings;

import java.util.List;

/**
 * 模型设置文件的完整快照。
 *
 * @param activeModelId 当前激活的持久化模型 id；null 表示回退到环境配置
 * @param models 按用户创建顺序保存的模型配置
 */
public record ModelSettings(String activeModelId, List<ModelConfig> models) {

    /** 防止调用方在保存期间修改列表，并把缺失列表规范为空配置。 */
    public ModelSettings {
        models = models == null ? List.of() : List.copyOf(models);
    }
}
