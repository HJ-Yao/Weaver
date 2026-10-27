package cn.yhjyhj.weaver.core.loop.context;

import cn.yhjyhj.weaver.core.events.SessionSnapshot;
import cn.yhjyhj.weaver.core.loop.intent.Intent;
import cn.yhjyhj.weaver.core.model.StreamChunk;
import cn.yhjyhj.weaver.core.model.StreamSink;
import java.util.ArrayList;
import java.util.List;

/**
 * 贯穿单步策略链的可变上下文。
 *
 * <p>上下文只承载单步执行期间的状态；跨步事实仍以事件流为准。
 */
public final class RunContext {

    /** 未提供实时输出出口时使用的空实现。 */
    private static final StreamSink NO_OP_STREAM_SINK = new StreamSink() {
        @Override
        public void onDelta(String delta) {}

        @Override
        public void onToolCall(StreamChunk.ToolCall call) {}

        @Override
        public void onDone(StreamChunk.StopReason reason) {}
    };

    private final String sessionId; // 当前会话标识
    private final EventSink sink; // 当前运行的事件出口
    private final StreamSink streamSink; // 当前运行的实时输出出口
    /** 本步开始前从事件流回放得到的会话快照。 */
    private SessionSnapshot history = SessionSnapshot.empty();
    private Intent intent; // 本步识别出的用户意图
    private String responseText = ""; // 本步已累积的模型文本
    private final List<StreamChunk.ToolCall> pendingToolCalls = new ArrayList<>(); // 待执行工具调用
    private boolean modelDone; // 是否已收到模型的 Done 分片
    private boolean modelFailed; // 模型是否通过分片或订阅错误失败
    private StreamChunk.StopReason stopReason; // 模型结束原因；未收到 Done 时为 null
    private boolean toolExecuted; // 本次模型响应中的工具是否已执行

    /**
     * 创建使用空流式出口的单步上下文。
     *
     * @param sessionId 当前会话标识
     * @param sink 本步的事件出口
     */
    public RunContext(String sessionId, EventSink sink) {
        this(sessionId, sink, NO_OP_STREAM_SINK);
    }

    /**
     * 创建单步上下文。
     *
     * @param sessionId 当前会话标识
     * @param sink 本步的事件出口
     * @param streamSink 本步的实时流式输出出口
     */
    public RunContext(String sessionId, EventSink sink, StreamSink streamSink) {
        this.sessionId = sessionId;
        this.sink = sink;
        this.streamSink = streamSink;
    }

    public String getSessionId() {
        return sessionId;
    }

    public EventSink getSink() {
        return sink;
    }

    public StreamSink getStreamSink() {
        return streamSink;
    }

    public SessionSnapshot getHistory() {
        return history;
    }

    public void setHistory(SessionSnapshot history) {
        this.history = history;
    }

    public Intent getIntent() {
        return intent;
    }

    public void setIntent(Intent intent) {
        this.intent = intent;
    }

    public String getResponseText() {
        return responseText;
    }

    public void setResponseText(String responseText) {
        this.responseText = responseText;
    }

    public List<StreamChunk.ToolCall> getPendingToolCalls() {
        return pendingToolCalls;
    }

    public void setPendingToolCalls(List<StreamChunk.ToolCall> pendingToolCalls) {
        this.pendingToolCalls.clear();
        this.pendingToolCalls.addAll(pendingToolCalls);
    }

    public boolean isModelDone() {
        return modelDone;
    }

    public void setModelDone(boolean modelDone) {
        this.modelDone = modelDone;
    }

    public boolean isModelFailed() {
        return modelFailed;
    }

    public void setModelFailed(boolean modelFailed) {
        this.modelFailed = modelFailed;
    }

    public StreamChunk.StopReason getStopReason() {
        return stopReason;
    }

    public void setStopReason(StreamChunk.StopReason stopReason) {
        this.stopReason = stopReason;
    }

    public boolean isToolExecuted() {
        return toolExecuted;
    }

    public void setToolExecuted(boolean toolExecuted) {
        this.toolExecuted = toolExecuted;
    }
}
