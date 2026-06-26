package org.example.vicky.script

import org.mozilla.javascript.Context
import org.mozilla.javascript.ScriptableObject
import java.io.File

data class ScriptConfig(
    val scriptsDir: File,
    val enabled: Boolean = true,
    val tsCompilerOptions: TsCompilerOptions = TsCompilerOptions(),
)

data class TsCompilerOptions(
    val target: String = "ES5",
    val module: String = "ES2015",
    val strict: Boolean = true,
)

data class ScriptExports(
    val name: String,
    val description: String,
    val parameters: String,
    val executeFn: Any?,
    val onLoadFn: Any? = null,
    val onUnloadFn: Any? = null,
    val fileName: String,
    val rhinoScope: ScriptableObject? = null,
    val rhinoContext: Context? = null,
    @Volatile var loaded: Boolean = false,
    @Volatile var loading: Boolean = false,
)
