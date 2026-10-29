package cn.yhjyhj.weaver.server.model;

import cn.yhjyhj.weaver.core.model.StreamChunk;
import java.util.Map;
import java.util.TreeMap;

/**
 * 单次 Anthropic 流请求的聚合状态。
 */
final class AnthropicStreamState {

    private final Map<Integer, AnthropicToolCallAccumulator> toolCalls = new TreeMap<>(); // 按内容块 index 聚合并排序
    private StreamChunk.StopReason stopReason = StreamChunk.StopReason.STOP; // 默认自然结束

    Map<Integer, AnthropicToolCallAccumulator> toolCalls() {
        return toolCalls;
    }

    StreamChunk.StopReason stopReason() {
        return stopReason;
    }

    void stopReason(StreamChunk.StopReason stopReason) {
        this.stopReason = stopReason;
    }
}
