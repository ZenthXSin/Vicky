package org.example.vicky.script

internal object ScriptRuntimePlatform {
    val isAndroid: Boolean by lazy {
        val runtimeName = System.getProperty("java.runtime.name").orEmpty()
        val vmName = System.getProperty("java.vm.name").orEmpty()
        runtimeName.contains("Android", ignoreCase = true) ||
            vmName.contains("Dalvik", ignoreCase = true) ||
            runCatching { Class.forName("android.os.Build", false, ScriptRuntimePlatform::class.java.classLoader) }.isSuccess
    }
}
