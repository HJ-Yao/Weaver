package cn.yhjyhj.weaver.server.api.dto;

/**
 * 工作目录浏览结果中的子目录。
 *
 * @param name 目录名称
 * @param path 目录绝对路径
 */
public record WorkspaceEntry(String name, String path) {}
