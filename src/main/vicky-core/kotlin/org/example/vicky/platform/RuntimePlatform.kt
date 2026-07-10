package org.example.vicky.platform

/** Runtime checks that do not introduce a compile-time Android dependency. */
internal object RuntimePlatform {
    val isAndroid: Boolean by lazy {
        val runtimeName = System.getProperty("java.runtime.name").orEmpty()
        val vmName = System.getProperty("java.vm.name").orEmpty()
        runtimeName.contains("Android", ignoreCase = true) ||
            vmName.contains("Dalvik", ignoreCase = true) ||
            runCatching { Class.forName("android.os.Build", false, RuntimePlatform::class.java.classLoader) }.isSuccess
    }
}
