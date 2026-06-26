package org.example.vicky.script

import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Undefined
import java.io.File
import java.lang.reflect.Modifier
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.LocalTime
import java.time.Instant
import java.time.Duration
import java.time.Period
import java.util.UUID
import java.util.Date
import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet
import java.util.concurrent.ConcurrentHashMap
import org.example.vicky.skill.Skill
import org.example.vicky.skill.SkillManager

// ─── 元数据结构 ────────────────────────────────────────────

data class ClassMeta(
    val simpleName: String,
    val fullClassName: String,
    val isObject: Boolean,
    val isEnum: Boolean,
    val fields: List<FieldInfo>,
    val constructors: List<ConstructorInfo>,
    val methods: List<MethodInfo>,
)

data class FieldInfo(val name: String, val type: String, val isStatic: Boolean)

data class ConstructorInfo(val params: List<ParamInfo>)

data class MethodInfo(val name: String, val returnType: String, val params: List<ParamInfo>)

data class ParamInfo(val name: String, val type: String)

// ─── 注册表 ────────────────────────────────────────────────

/**
 * 反射扫描 classpath，自动注入类到 Rhino 全局作用域。
 * 脚本中可直接使用类名，无需 import。
 *
 * Kotlin object 单例自动注入 INSTANCE 实例。
 * 扫描时收集类元数据，可生成 API 技能文档。
 */
object ClassAutoRegistry {

    private val classMap = ConcurrentHashMap<String, Class<*>>()
    private val metaMap = ConcurrentHashMap<String, ClassMeta>()
    @Volatile private var initialized = false
    @Volatile private var skillsGenerated = false

    private val predefinedClasses: List<Class<*>> = listOf(
        File::class.java,
        Path::class.java,
        Files::class.java,
        LocalDateTime::class.java,
        LocalDate::class.java,
        LocalTime::class.java,
        Instant::class.java,
        Duration::class.java,
        Period::class.java,
        UUID::class.java,
        Date::class.java,
        ArrayList::class.java,
        HashMap::class.java,
        HashSet::class.java,
        StringBuilder::class.java,
        String::class.java,
        StandardCopyOption::class.java,
    )

    fun init() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            for (cls in predefinedClasses) {
                classMap.putIfAbsent(cls.simpleName, cls)
            }
            scanPackage("org.example.vicky")
            scanPackage("kotlinx.serialization.json")
            scanPackage("java.util")
            initialized = true
            println("[Vicky][script] ClassAutoRegistry 已初始化: ${classMap.size} 个类可用")
        }
    }

    /**
     * 向 Rhino scope 注入所有已注册的类。
     * Kotlin object 单例注入 INSTANCE 实例，普通类注入构造函数代理。
     * 末尾自动生成 runtime-api 技能。
     */
    fun injectAll(ctx: Context, scope: ScriptableObject) {
        init()

        for ((simpleName, cls) in classMap) {
            injectClass(ctx, scope, simpleName, cls)
        }

        // 注入 Java.type() 兜底
        val javaObj = ctx.newObject(scope)
        val typeFn = object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any? {
                if (args.isNotEmpty()) {
                    val fqn = Context.toString(args[0])
                    val clazz = Class.forName(fqn)
                    return createClassProxy(cx, scope as ScriptableObject, clazz)
                }
                return Undefined.instance
            }
        }
        ScriptableObject.putProperty(javaObj, "type", typeFn)
        ScriptableObject.putProperty(scope, "Java", javaObj)

        // 自动生成 runtime-api 技能
        if (!skillsGenerated) {
            skillsGenerated = true
            generateSkills()
        }
    }

    fun register(cls: Class<*>) {
        classMap.putIfAbsent(cls.simpleName, cls)
        collectMeta(cls)
    }

    fun register(name: String, cls: Class<*>) {
        classMap[name] = cls
        collectMeta(cls)
    }

    fun registeredNames(): Set<String> = classMap.keys.toSet()

    /** 获取已收集的类元数据。 */
    fun classMetadata(): Map<String, ClassMeta> = metaMap.toMap()

    // ─── 注入 ────────────────────────────────────────────────

    private fun injectClass(ctx: Context, scope: ScriptableObject, name: String, cls: Class<*>) {
        // 跳过非 public 类和内部类（如 kotlinx.serialization 的 Tombstone、JsonLiteralSerializer 等）
        if (!Modifier.isPublic(cls.modifiers) || cls.isSynthetic || cls.isAnonymousClass) return
        try {
            // 检测 Kotlin object 单例（INSTANCE 字段）
            val instanceField = cls.declaredFields.firstOrNull {
                it.name == "INSTANCE" &&
                    it.type == cls &&
                    Modifier.isStatic(it.modifiers) &&
                    Modifier.isPublic(it.modifiers)
            }
            if (instanceField != null) {
                val instance = instanceField.get(null)
                val wrapped = Context.javaToJS(instance, scope)
                ScriptableObject.putProperty(scope, name, wrapped)
            } else {
                // 普通类：构造函数代理
                val proxy = createClassProxy(ctx, scope, cls)
                if (cls.isEnum) {
                    for (constant in cls.enumConstants) {
                        val enumVal = constant as Enum<*>
                        ScriptableObject.putProperty(proxy, enumVal.name, Context.javaToJS(constant, scope))
                    }
                }
                ScriptableObject.putProperty(scope, name, proxy)
            }
        } catch (e: Exception) {
            // 只在首次注入失败时打印，避免每个脚本加载都重复
            if (name !in injectFailedNames) {
                injectFailedNames.add(name)
                println("[Vicky][script] 注入类 $name 失败: ${e.message}")
            }
        }
    }

    /** 记录注入失败的类名，避免重复日志。 */
    private val injectFailedNames = mutableSetOf<String>()

    /**
     * 创建一个可调用的 JS 函数，作为 Java 类的构造器代理。
     * 调用时：`new File("/path")` 或 `File("/path")`
     */
    private fun createClassProxy(ctx: Context, scope: ScriptableObject, cls: Class<*>): BaseFunction {
        return object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any? {
                return tryCreateInstance(cx, scope as ScriptableObject, cls, args)
            }

            override fun construct(cx: Context, scope: Scriptable, args: Array<out Any?>): Scriptable {
                val instance = tryCreateInstance(cx, scope as ScriptableObject, cls, args)
                    ?: throw ScriptException("Cannot create ${cls.simpleName}")
                val wrapped = Context.javaToJS(instance, scope as ScriptableObject)
                return wrapped as? Scriptable
                    ?: throw ScriptException("Cannot wrap ${cls.simpleName} as JS object")
            }
        }
    }

    private fun tryCreateInstance(cx: Context, scope: ScriptableObject, cls: Class<*>, args: Array<out Any?>): Any? {
        for (ctor in cls.constructors) {
            if (ctor.parameterCount == args.size) {
                try {
                    val converted = convertArgs(cx, scope, ctor.parameterTypes, args)
                    return ctor.newInstance(*converted)
                } catch (_: Exception) { continue }
            }
        }
        if (args.isEmpty()) {
            try {
                return cls.getDeclaredConstructor().newInstance()
            } catch (e: Exception) {
                throw ScriptException("Cannot create ${cls.simpleName}: ${e.message}")
            }
        }
        throw ScriptException("Cannot create ${cls.simpleName}: no matching constructor for ${args.size} args")
    }

    private fun convertArgs(cx: Context, scope: ScriptableObject, types: Array<Class<*>>, args: Array<out Any?>): Array<Any?> {
        return types.mapIndexed { i, type -> convertValue(args[i], type) }.toTypedArray()
    }

    private fun convertValue(value: Any?, targetType: Class<*>): Any? {
        if (value == null || value == Undefined.instance) return null
        return when {
            targetType == String::class.java -> Context.toString(value)
            targetType == Int::class.java || targetType == java.lang.Integer::class.java ->
                (value as? Number)?.toInt() ?: Context.toString(value).toIntOrNull()
            targetType == Long::class.java || targetType == java.lang.Long::class.java ->
                (value as? Number)?.toLong() ?: Context.toString(value).toLongOrNull()
            targetType == Double::class.java || targetType == java.lang.Double::class.java ->
                (value as? Number)?.toDouble() ?: Context.toString(value).toDoubleOrNull()
            targetType == Boolean::class.java || targetType == java.lang.Boolean::class.java ->
                Context.toBoolean(value)
            targetType.isInstance(value) -> value
            else -> value
        }
    }

    // ─── 扫描 ────────────────────────────────────────────────

    private fun scanPackage(packageName: String) {
        val classLoader = ClassAutoRegistry::class.java.classLoader ?: return
        val path = packageName.replace('.', '/')
        try {
            val resources = classLoader.getResources(path)
            while (resources.hasMoreElements()) {
                val resource = resources.nextElement()
                val protocol = resource.protocol
                when (protocol) {
                    "file" -> {
                        val file = File(URLDecoder.decode(resource.path, "UTF-8"))
                        if (file.isDirectory) scanDirectory(file, packageName)
                    }
                    "jar" -> {
                        scanJar(packageName, resource.toString())
                    }
                }
            }
        } catch (e: Exception) {
            println("[Vicky][script] scanPackage($packageName) 失败: ${e.message}")
        }
    }

    /** 从 JAR 扫描指定包下的类。 */
    private fun scanJar(packageName: String, jarUrl: String) {
        val prefix = packageName.replace('.', '/') + "/"
        val jarPath = jarUrl.removePrefix("jar:file:").substringBefore("!")
        val jarFile = try {
            java.util.jar.JarFile(URLDecoder.decode(jarPath, "UTF-8"))
        } catch (_: Exception) { return }

        for (entry in jarFile.entries()) {
            if (!entry.isDirectory && entry.name.startsWith(prefix) && entry.name.endsWith(".class")) {
                val className = entry.name.removeSuffix(".class").replace('/', '.')
                try {
                    val cls = Class.forName(className)
                    if (shouldRegister(cls)) {
                        classMap.putIfAbsent(cls.simpleName, cls)
                        collectMeta(cls)
                    }
                } catch (_: Exception) {}
            }
        }
        jarFile.close()
    }

    private fun scanDirectory(directory: File, packageName: String) {
        if (!directory.exists() || !directory.isDirectory) return
        for (file in directory.listFiles() ?: return) {
            if (file.isDirectory) {
                scanDirectory(file, "$packageName.${file.name}")
            } else if (file.name.endsWith(".class")) {
                val className = "$packageName.${file.name.removeSuffix(".class")}"
                try {
                    val cls = Class.forName(className)
                    if (shouldRegister(cls)) {
                        val existing = classMap[cls.simpleName]
                        if (existing == null || cls.packageName.startsWith("org.example.vicky")) {
                            classMap[cls.simpleName] = cls
                            collectMeta(cls)
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    /** 判断类是否应该注册：public、非合成、非匿名、非本地。 */
    private fun shouldRegister(cls: Class<*>): Boolean =
        cls.simpleName.isNotEmpty() &&
            Modifier.isPublic(cls.modifiers) &&
            !cls.isSynthetic &&
            !cls.isAnonymousClass &&
            !cls.isLocalClass

    // ─── 元数据收集 ──────────────────────────────────────────

    private fun collectMeta(cls: Class<*>) {
        try {
            val isObj = cls.declaredFields.any {
                it.name == "INSTANCE" && it.type == cls && Modifier.isStatic(it.modifiers)
            }

            val fields = cls.declaredFields
                .filter { !it.isSynthetic }
                .map { FieldInfo(it.name, it.type.simpleName, Modifier.isStatic(it.modifiers)) }

            val constructors = cls.constructors
                .map { ctor ->
                    ConstructorInfo(ctor.parameters.mapIndexed { i, p -> ParamInfo(p.name ?: "arg$i", p.type.simpleName) })
                }

            val methods = cls.declaredMethods
                .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
                .map { m ->
                    MethodInfo(
                        m.name,
                        m.returnType.simpleName,
                        m.parameters.mapIndexed { i, p -> ParamInfo(p.name ?: "arg$i", p.type.simpleName) }
                    )
                }

            metaMap[cls.simpleName] = ClassMeta(
                simpleName = cls.simpleName,
                fullClassName = cls.name,
                isObject = isObj,
                isEnum = cls.isEnum,
                fields = fields,
                constructors = constructors,
                methods = methods,
            )
        } catch (_: Exception) {}
    }

    // ─── 技能生成 ────────────────────────────────────────────

    private fun generateSkills() {
        try {
            // 注册分组
            SkillManager.registerGroup("runtime-api", "Vicky runtime classes and objects auto-injected into script scope.")

            // 确定技能写入目录
            val skillsDir = resolveSkillsDir() ?: run {
                println("[Vicky][script] 无法确定 skills 目录，跳过技能文件写入")
                return
            }
            val groupDir = File(skillsDir, "runtime-api")

            // 写入 group.md
            val groupMdFile = File(groupDir, "group.md")
            if (!groupMdFile.exists()) {
                groupDir.mkdirs()
                groupMdFile.writeText(buildString {
                    appendLine("---")
                    appendLine("name: runtime-api")
                    appendLine("description: Vicky runtime classes and objects auto-injected into script scope.")
                    appendLine("---")
                    appendLine()
                    appendLine("此分组包含 Vicky 运行时所有自动注入的类和对象的 API 文档。")
                    appendLine("每个类/object 对应一个技能，包含字段、构造方法、公开方法的完整签名。")
                }, Charsets.UTF_8)
            }

            // 为每个有元数据的类生成技能
            for ((name, meta) in metaMap) {
                val body = buildSkillBody(meta)
                val description = if (meta.isObject) {
                    "Kotlin object singleton: ${meta.fullClassName}. Auto-injected into script scope."
                } else {
                    "Kotlin class: ${meta.fullClassName}."
                }

                SkillManager.register(Skill(name, description, body, group = "runtime-api"))

                // 写入文件
                val skillDir = File(groupDir, name)
                val skillFile = File(skillDir, "SKILL.md")
                if (!skillFile.exists()) {
                    skillDir.mkdirs()
                    skillFile.writeText(buildString {
                        appendLine("---")
                        appendLine("name: $name")
                        appendLine("description: $description")
                        appendLine("group: runtime-api")
                        appendLine("---")
                        appendLine()
                        append(body)
                    }, Charsets.UTF_8)
                }
            }

            println("[Vicky][script] 已注册 ${metaMap.size} 个 runtime-api 技能到 SkillManager")
        } catch (e: Exception) {
            println("[Vicky][script] 技能生成失败: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun buildSkillBody(meta: ClassMeta): String = buildString {
        appendLine("# ${meta.simpleName}")
        appendLine()
        if (meta.isObject) {
            appendLine("Kotlin object (singleton). In scripts, use directly as `${meta.simpleName}`.")
        } else {
            appendLine("Kotlin class. Create instances with `new ${meta.simpleName}(...)` or `${meta.simpleName}(...)`.")
        }
        appendLine()
        appendLine("Full class: `${meta.fullClassName}`")

        if (meta.fields.isNotEmpty()) {
            appendLine()
            appendLine("## Fields")
            for (f in meta.fields) {
                val static = if (f.isStatic) " (static)" else ""
                appendLine("- `${f.name}`: ${f.type}$static")
            }
        }

        if (meta.constructors.isNotEmpty()) {
            appendLine()
            appendLine("## Constructors")
            for ((i, ctor) in meta.constructors.withIndex()) {
                val params = ctor.params.joinToString(", ") { "${it.name}: ${it.type}" }
                appendLine("- `${meta.simpleName}($params)`")
            }
        }

        if (meta.methods.isNotEmpty()) {
            appendLine()
            appendLine("## Methods")
            for (m in meta.methods) {
                val params = m.params.joinToString(", ") { "${it.name}: ${it.type}" }
                appendLine("- `${m.name}($params): ${m.returnType}`")
            }
        }

        if (meta.isObject) {
            appendLine()
            appendLine("## Usage in TypeScript")
            appendLine("```typescript")
            appendLine("// Direct access — no constructor needed")
            appendLine("var instance = ${meta.simpleName};")
            if (meta.methods.isNotEmpty()) {
                val firstMethod = meta.methods.first()
                val sampleArgs = firstMethod.params.joinToString(", ") { "..." }
                appendLine("var result = ${meta.simpleName}.${firstMethod.name}($sampleArgs);")
            }
            appendLine("```")
        }
    }

    private fun resolveSkillsDir(): File? {
        // 尝试通过 ConfigManager 获取 config/skills 目录
        try {
            val configMgrClass = Class.forName("org.example.vicky.config.ConfigManager")
            val getConfigDirMethod = configMgrClass.getMethod("getConfigDir")
            val configDir = getConfigDirMethod.invoke(null) as? File
            if (configDir != null) {
                return File(configDir, "skills")
            }
        } catch (_: Exception) {}
        // 回退：当前工作目录
        return File(System.getProperty("user.dir"), "config/skills")
    }
}
