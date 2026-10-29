package cn.yhjyhj.weaver.server.model;

import cn.yhjyhj.weaver.core.model.StreamChunk;

/**
 * 聚合一个 OpenAI 流式工具调用的标识、名称与参数分片。
 */
final class OpenAiToolCallAccumulator {

    private final StringBuilder callId = new StringBuilder(); // 跨分片给出的调用标识
    private final StringBuilder name = new StringBuilder(); // 跨分片给出的工具名称
    private final StringBuilder arguments = new StringBuilder(); // 跨分片拼接的参数 JSON

    void appendId(String fragment) {
        callId.append(fragment);
    }

    void appendName(String fragment) {
        name.append(fragment);
    }

    void appendArguments(String fragment) {
        arguments.append(fragment);
    }

    StreamChunk.ToolCall toToolCall() {
        return new StreamChunk.ToolCall(
                callId.toString(), name.toString(), arguments.toString());
    }
}
