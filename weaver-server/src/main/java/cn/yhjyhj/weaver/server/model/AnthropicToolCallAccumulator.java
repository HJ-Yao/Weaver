package cn.yhjyhj.weaver.server.model;

import cn.yhjyhj.weaver.core.model.StreamChunk;

/**
 * 聚合一个 Anthropic 流式工具调用的参数分片。
 */
final class AnthropicToolCallAccumulator {

    private final String callId; // content_block_start 给出的调用标识
    private final String name; // content_block_start 给出的工具名称
    private final StringBuilder arguments = new StringBuilder(); // 跨 delta 拼接的参数 JSON
    private boolean completed; // content_block_stop 是否已确认参数结束

    AnthropicToolCallAccumulator(String callId, String name) {
        this.callId = callId;
        this.name = name;
    }

    void appendArguments(String fragment) {
        arguments.append(fragment);
    }

    void complete() {
        completed = true;
    }

    boolean completed() {
        return completed;
    }

    StreamChunk.ToolCall toToolCall() {
        return new StreamChunk.ToolCall(callId, name, arguments.toString());
    }
}
