package cn.yhjyhj.weaver.core.model;

import java.util.concurrent.Flow;

/** 模型调用的最小 SPI；核心不绑定任何模型厂商。 */
public interface ModelClient {

    /**
     * 返回本次模型调用的分片流；实现方负责流的生命周期。
     *
     * @param request 模型输入与历史快照
     * @return 背压可控的响应分片流
     */
    Flow.Publisher<StreamChunk> stream(ModelRequest request);

}
