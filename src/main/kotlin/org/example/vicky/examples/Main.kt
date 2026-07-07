package org.example.vicky.examples

/**
 * 统一启动入口。
 *
 * 用法：
 *   java -jar Vicky.jar            → 普通 OneBot Agent 模式（默认）
 *   java -jar Vicky.jar --vibe     → Vibe Code CLI 模式（交互）
 *   java -jar Vicky.jar --vibe task text here → 直接执行单次 Vibe 任务
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "--vibe") {
        vibeCodeCliMain(args.drop(1).toTypedArray())
    } else {
        consoleMain()
    }
}
