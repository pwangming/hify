package com.hify.tool.service.builtin;

/**
 * 内置工具执行器契约（仅 tool 模块内部使用，不进 api）。
 * name 须与 tool 表 builtin 行的 name 一致；inputSchema 为工具入参的 JSON Schema（模型据此构造参数）。
 * execute 接收模型给出的 JSON 参数字符串，返回给模型的结果文本——**任何失败都返回错误文本，绝不抛异常**
 * （让模型自行恢复/致歉，不中断整轮 Agent 循环）。
 */
public interface BuiltinTool {

    String name();

    String inputSchema();

    String execute(String argsJson);
}
