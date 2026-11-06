package cn.yhjyhj.weaver.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 握手完成后得到的服务器信息。
 *
 * @param name 服务器实现名
 * @param version 服务器实现版本
 * @param protocolVersion 双方最终使用的协议版本
 * @param capabilities 服务器声明的能力
 * @param instructions 服务器给模型的使用说明；没有时为 null
 */
public record McpServerInfo(
        String name,
        String version,
        String protocolVersion,
        JsonNode capabilities,
        String instructions) {}
