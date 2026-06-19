package org.example.vicky.logging

import net.mamoe.mirai.utils.MiraiLogger
import net.mamoe.mirai.utils.SilentLogger

class SilentMiraiLoggerFactory : MiraiLogger.Factory {
    override fun create(requester: Class<*>, identity: String?): MiraiLogger = SilentLogger
}
