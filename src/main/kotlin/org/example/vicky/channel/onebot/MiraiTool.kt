package org.example.vicky.channel.onebot

import net.mamoe.mirai.Bot
import org.example.vicky.tool.Tool

/**
 * mirai 工具的抽象基类，持有 [Bot] 引用。
 * 所有需要直接操作 QQ 的工具都应继承此类。
 */
abstract class MiraiTool(
    protected val bot: Bot,
) : Tool()
