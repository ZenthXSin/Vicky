package org.example.vicky.examples

fun main(args: Array<String>) {
    when (args.firstOrNull()?.lowercase()) {
        "--vibe" -> vibeCodeCliMain(args.drop(1).toTypedArray())
        "--normal", "--console" -> consoleMain()
        "--help", "-h" -> printUsage()
        null -> selectStartupMode()
        else -> {
            println("未知启动参数: ${args.first()}")
            printUsage()
        }
    }
}

private fun selectStartupMode() {
    println("请选择启动模式：")
    println("1) Normal / default mode")
    println("2) Vibe mode")
    print("输入选择 [1]: ")

    when (readlnOrNull()?.trim()?.lowercase().orEmpty()) {
        "", "1", "normal", "default", "console" -> consoleMain()
        "2", "vibe" -> vibeCodeCliMain(emptyArray())
        else -> {
            println("无法识别选择，默认进入 Normal mode。")
            consoleMain()
        }
    }
}

private fun printUsage() {
    println(
        """
        Usage:
          java -jar Vicky.jar              启动菜单
          java -jar Vicky.jar --normal     普通模式
          java -jar Vicky.jar --console    普通模式
          java -jar Vicky.jar --vibe       Vibe 交互模式
          java -jar Vicky.jar --vibe <任务> 单次 Vibe 任务
        """.trimIndent()
    )
}
