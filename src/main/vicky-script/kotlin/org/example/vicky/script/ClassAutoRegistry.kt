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

/** 标记接口：注入到 Rhino scope 的 Java 类代理 BaseFunction 都实现它，供 extend() 反查 Class。 */
interface ClassProxy {
    val targetClass: Class<*>
}

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
                collectMeta(cls)
            }
            if (!ScriptRuntimePlatform.isAndroid) {
                scanJvmClasspath()
            }
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

    /** Android/Dex hosts can explicitly expose application classes to scripts. */
    fun registerAll(vararg classes: Class<*>) {
        classes.forEach(::register)
    }

    fun registeredNames(): Set<String> = classMap.keys.toSet()

    /** 获取已收集的类元数据。 */
    fun classMetadata(): Map<String, ClassMeta> = metaMap.toMap()

    /**
     * 从脚本传入的"类引用"反查 Java Class。
     * 支持：injectAll 注入的 BaseFunction 代理（ClassProxy）、NativeJavaClass、原始 Class。
     */
    fun extractClass(value: Any?): Class<*>? = when (value) {
        is ClassProxy -> value.targetClass
        is org.mozilla.javascript.NativeJavaClass -> value.classObject
        is Class<*> -> value
        else -> null
    }

    // ─── 注入 ────────────────────────────────────────────────

    /** JavaScript 内置对象名，注入会破坏 Rhino 原型链。 */
    private val JS_RESERVED = setOf(
        "Object", "Function", "Array", "String", "Number", "Boolean", "Symbol",
        "Error", "TypeError", "RangeError", "ReferenceError", "SyntaxError",
        "RegExp", "Date", "Math", "JSON", "Map", "Set", "WeakMap", "WeakSet",
        "Promise", "Proxy", "Reflect", "Intl", "Console",
        "Iterator", "Generator", "GeneratorFunction", "AsyncFunction",
        "ArrayBuffer", "DataView", "Float32Array", "Float64Array",
        "Int8Array", "Int16Array", "Int32Array", "Uint8Array", "Uint16Array",
        "Uint32Array", "Uint8ClampedArray",
        "URIError", "EvalError", "NaN", "Infinity", "undefined",
        "eval", "isFinite", "isNaN", "parseFloat", "parseInt",
        "decodeURI", "decodeURIComponent", "encodeURI", "encodeURIComponent",
        "Java", "Packages", "java", "javax", "org", "com", "edu", "net",
        "println", "print", "quit", "exit", "load", "loadClass",
    )

    private fun injectClass(ctx: Context, scope: ScriptableObject, name: String, cls: Class<*>) {
        // 跳过非 public 类和内部类（如 kotlinx.serialization 的 Tombstone、JsonLiteralSerializer 等）
        if (!Modifier.isPublic(cls.modifiers) || cls.isSynthetic || cls.isAnonymousClass) return
        // 跳过 JS 内置名，避免覆盖 Rhino 原型链
        if (name in JS_RESERVED) return
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
                // 注入 companion object 属性（如 AgentMode.VERBOSE）
                val companionField = cls.declaredFields.firstOrNull {
                    it.name == "Companion" && Modifier.isStatic(it.modifiers)
                }
                if (companionField != null) {
                    try {
                        companionField.isAccessible = true
                        val companion = companionField.get(null)
                        if (companion != null) {
                            for (m in companion.javaClass.methods) {
                                if (m.parameterCount != 0 || Modifier.isStatic(m.modifiers)) continue
                                if (!m.name.startsWith("get")) continue
                                val propName = m.name.removePrefix("get")
                                if (propName.isEmpty() || propName == "Class") continue
                                try {
                                    val v = m.invoke(companion) ?: continue
                                    ScriptableObject.putProperty(proxy, propName, Context.javaToJS(v, scope))
                                } catch (_: Exception) {}
                            }
                        }
                    } catch (_: Exception) {}
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
        val isAbstract = Modifier.isAbstract(cls.modifiers) && !cls.isInterface
        // 必须把 prototype 指向 Function.prototype，否则脚本里 `Foo.call/apply/bind` 找不到方法，
        // Rhino 会拿到非 callable 值并用 getDefaultValue(Function.class) 强转，触发 "Cannot find default value"。
        val funcProto = ScriptableObject.getFunctionPrototype(scope)
        return object : BaseFunction(scope, funcProto), ClassProxy {
            override val targetClass: Class<*> = cls

            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any? {
                // ES5 super-constructor 模式：`_super.call(this, ...)` 在 TS class extends 编译产物中调用父类构造。
                // Java 抽象类不能反射 newInstance，且 thisObj 是 JS 对象不是 Java 实例 — 无法注入字段。
                // 折中：返回 thisObj 让 `_super.call(...) || this` 短路通过，脚本可以加载，
                // 但子类不会真的继承父类的 Java 字段/方法。
                if (isAbstract) return thisObj
                return tryCreateInstance(cx, scope as ScriptableObject, cls, args)
            }

            override fun construct(cx: Context, scope: Scriptable, args: Array<out Any?>): Scriptable {
                if (isAbstract) {
                    throw ScriptException("Cannot instantiate abstract class ${cls.simpleName}")
                }
                val instance = tryCreateInstance(cx, scope as ScriptableObject, cls, args)
                    ?: throw ScriptException("Cannot create ${cls.simpleName}")
                val wrapped = Context.javaToJS(instance, scope as ScriptableObject)
                return wrapped as? Scriptable
                    ?: throw ScriptException("Cannot wrap ${cls.simpleName} as JS object")
            }
        }
    }

    private fun tryCreateInstance(cx: Context, scope: ScriptableObject, cls: Class<*>, args: Array<out Any?>): Any? {
        // SAM 接口 + 单个 JS function：通过 Proxy 创建函数式接口适配器
        if (cls.isInterface && args.size == 1 && args[0] is org.mozilla.javascript.Function) {
            return createSamAdapter(scope, cls, args[0] as org.mozilla.javascript.Function)
        }
        // 单个 JS 对象参数：按字段名映射到 Kotlin 主构造的命名参数（支持默认值）
        if (args.size == 1) {
            val first = args[0]
            if (first is Scriptable && first !is org.mozilla.javascript.NativeArray && first !is org.mozilla.javascript.Wrapper) {
                val instance = tryCreateFromNamedArgs(cls, first)
                if (instance != null) return instance
            }
        }

        for (ctor in cls.constructors) {
            if (ctor.parameterCount == args.size) {
                try {
                    val converted = convertArgs(cx, scope, ctor.parameterTypes, args)
                    return ctor.newInstance(*converted)
                } catch (_: Exception) { continue }
            }
        }
        // 位置参数少于主构造参数数：尾部用 Kotlin 默认值（通过 $default 合成构造 + bitmask）
        if (args.isNotEmpty()) {
            val instance = tryCreateWithTrailingDefaults(cx, scope, cls, args)
            if (instance != null) return instance
        }

        // Kotlin @JvmInline value class: 构造函数为 private，通过 public static box-impl(...) 创建装箱实例
        if (args.isNotEmpty()) {
            val boxImpl = cls.declaredMethods.firstOrNull {
                it.name == "box-impl" &&
                    Modifier.isStatic(it.modifiers) &&
                    Modifier.isPublic(it.modifiers) &&
                    it.parameterCount == args.size
            }
            if (boxImpl != null) {
                try {
                    val converted = convertArgs(cx, scope, boxImpl.parameterTypes, args)
                    return boxImpl.invoke(null, *converted)
                } catch (_: Exception) {}
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

    /**
     * Kotlin data class 命名参数支持：脚本 `new Foo({a: 1, b: 2})` 通过 `$default` 合成构造调用。
     *
     * 主构造可能为 private（含默认参数时），所以从 declaredConstructors 取参数数最大且不含 DefaultConstructorMarker 的。
     * 调用合成构造时填充默认占位值并设置 bitmask（每 32 个参数 1 个 int）。
     */
    private fun tryCreateFromNamedArgs(cls: Class<*>, jsObj: Scriptable): Any? {
        val declared = try { cls.declaredConstructors } catch (_: Exception) { return null }
        val markerSimpleName = "DefaultConstructorMarker"

        val primary = declared
            .filter { it.parameterCount > 0 && it.parameterTypes.last().simpleName != markerSimpleName }
            .maxByOrNull { it.parameterCount } ?: return null
        val primaryCount = primary.parameterCount

        val maskCount = (primaryCount + 31) / 32
        val defaultCtor = declared.firstOrNull {
            it.parameterCount == primaryCount + maskCount + 1 &&
                it.parameterTypes.last().simpleName == markerSimpleName
        }

        val params = try { primary.parameters } catch (_: Exception) { return null }
        val paramTypes = primary.parameterTypes

        // Kotlin 默认不生成 MethodParameters，反射只能拿到 argN。回退到 declaredFields：
        // Kotlin data class 的实例字段名 = 构造参数名，按声明顺序一一对应。
        val fieldNames: List<String> = cls.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .map { it.name }
        val argRegex = Regex("arg\\d+")
        val names: Array<String?> = Array(primaryCount) { i ->
            val pname = params[i].name
            if (pname != null && !argRegex.matches(pname)) pname
            else fieldNames.getOrNull(i)
        }

        val values = arrayOfNulls<Any?>(primaryCount)
        val provided = BooleanArray(primaryCount)
        for (i in 0 until primaryCount) {
            val name = names[i] ?: continue
            if (jsObj.has(name, jsObj)) {
                val v = jsObj.get(name, jsObj)
                if (v != Scriptable.NOT_FOUND && v != Undefined.instance) {
                    values[i] = convertValue(v, paramTypes[i])
                    provided[i] = true
                }
            }
        }

        return try {
            if (defaultCtor != null) {
                val masks = IntArray(maskCount)
                for (i in 0 until primaryCount) {
                    if (!provided[i]) {
                        masks[i / 32] = masks[i / 32] or (1 shl (i % 32))
                        values[i] = defaultValueForType(paramTypes[i])
                    }
                }
                val finalArgs = arrayOfNulls<Any?>(primaryCount + maskCount + 1)
                for (i in 0 until primaryCount) finalArgs[i] = values[i]
                for (i in 0 until maskCount) finalArgs[primaryCount + i] = masks[i]
                finalArgs[finalArgs.lastIndex] = null
                defaultCtor.isAccessible = true
                defaultCtor.newInstance(*finalArgs)
            } else if (provided.all { it }) {
                primary.isAccessible = true
                primary.newInstance(*values)
            } else null
        } catch (_: Exception) { null }
    }

    /**
     * 创建 SAM 接口的 JS 函数适配器。
     * - 自动剥离 suspend 方法的 Continuation 参数，JS 同步执行后 resumeWith(Result.success(Unit))
     * - equals/hashCode/toString 内置默认实现
     */
    private fun createSamAdapter(scope: ScriptableObject, cls: Class<*>, jsFn: org.mozilla.javascript.Function): Any {
        val handler = java.lang.reflect.InvocationHandler { proxy, method, methodArgs ->
            when (method.name) {
                "equals" -> return@InvocationHandler (methodArgs?.getOrNull(0) === proxy)
                "hashCode" -> return@InvocationHandler System.identityHashCode(proxy)
                "toString" -> return@InvocationHandler "[JS adapter for ${cls.simpleName}]"
            }
            val rawArgs: Array<Any?> = (methodArgs ?: emptyArray()).map { it as Any? }.toTypedArray()
            @Suppress("UNCHECKED_CAST")
            val continuation: kotlin.coroutines.Continuation<Any?>? =
                rawArgs.lastOrNull() as? kotlin.coroutines.Continuation<Any?>
            val passArgs: Array<Any?> = if (continuation != null) rawArgs.dropLast(1).toTypedArray() else rawArgs

            val cx = Context.enter()
            val jsResult: Any? = try {
                val jsArgs: Array<Any?> = passArgs.map { Context.javaToJS(it, scope) }.toTypedArray()
                try {
                    jsFn.call(cx, scope, scope, jsArgs)
                } catch (e: Throwable) {
                    continuation?.resumeWith(Result.failure(e))
                    return@InvocationHandler if (continuation != null) kotlin.Unit else throw e
                }
            } finally {
                Context.exit()
            }
            if (continuation != null) kotlin.Unit else jsResult
        }
        return java.lang.reflect.Proxy.newProxyInstance(
            cls.classLoader ?: ClassAutoRegistry::class.java.classLoader,
            arrayOf(cls),
            handler
        )
    }

    /**
     * 位置参数省略尾部默认值：例如 `new InboundMessage("u","c","")` 调用 `(String,String,String,String=...)` 主构造，
     * 通过 $default 合成构造 + bitmask 让 Kotlin 用主构造定义的默认值。
     */
    private fun tryCreateWithTrailingDefaults(cx: Context, scope: ScriptableObject, cls: Class<*>, args: Array<out Any?>): Any? {
        val declared = try { cls.declaredConstructors } catch (_: Exception) { return null }
        val markerName = "DefaultConstructorMarker"
        val primary = declared
            .filter { it.parameterCount > 0 && it.parameterTypes.last().simpleName != markerName }
            .maxByOrNull { it.parameterCount } ?: return null
        if (args.size >= primary.parameterCount) return null  // for-ctor 循环已处理或没默认值

        val primaryCount = primary.parameterCount
        val maskCount = (primaryCount + 31) / 32
        val defaultCtor = declared.firstOrNull {
            it.parameterCount == primaryCount + maskCount + 1 &&
                it.parameterTypes.last().simpleName == markerName
        } ?: return null

        return try {
            val paramTypes = primary.parameterTypes
            val values = arrayOfNulls<Any?>(primaryCount)
            for (i in 0 until args.size) {
                values[i] = convertValue(args[i], paramTypes[i])
            }
            val masks = IntArray(maskCount)
            for (i in args.size until primaryCount) {
                masks[i / 32] = masks[i / 32] or (1 shl (i % 32))
                values[i] = defaultValueForType(paramTypes[i])
            }
            val finalArgs = arrayOfNulls<Any?>(primaryCount + maskCount + 1)
            for (i in 0 until primaryCount) finalArgs[i] = values[i]
            for (i in 0 until maskCount) finalArgs[primaryCount + i] = masks[i]
            finalArgs[finalArgs.lastIndex] = null
            defaultCtor.isAccessible = true
            defaultCtor.newInstance(*finalArgs)
        } catch (_: Exception) { null }
    }

    private fun defaultValueForType(t: Class<*>): Any? = when (t) {
        Boolean::class.javaPrimitiveType -> false
        Byte::class.javaPrimitiveType -> 0.toByte()
        Short::class.javaPrimitiveType -> 0.toShort()
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0f
        Double::class.javaPrimitiveType -> 0.0
        Char::class.javaPrimitiveType -> ' '
        else -> null
    }

    private fun convertArgs(cx: Context, scope: ScriptableObject, types: Array<Class<*>>, args: Array<out Any?>): Array<Any?> {
        return types.mapIndexed { i, type -> convertValue(args[i], type) }.toTypedArray()
    }

    private fun convertValue(value: Any?, targetType: Class<*>): Any? {
        if (value == null || value == Undefined.instance) return null
        // 拆掉 Rhino 的 Java wrapper（NativeJavaObject 等）
        val v = if (value is org.mozilla.javascript.Wrapper) value.unwrap() ?: return null else value
        return when {
            targetType == String::class.java -> when (v) {
                is String -> v
                else -> tryUnbox(v, String::class.java) as? String ?: Context.toString(v)
            }
            targetType == Int::class.java || targetType == java.lang.Integer::class.java ->
                (v as? Number)?.toInt() ?: Context.toString(v).toIntOrNull()
            targetType == Long::class.java || targetType == java.lang.Long::class.java ->
                (v as? Number)?.toLong() ?: Context.toString(v).toLongOrNull()
            targetType == Double::class.java || targetType == java.lang.Double::class.java ->
                (v as? Number)?.toDouble() ?: Context.toString(v).toDoubleOrNull()
            targetType == Boolean::class.java || targetType == java.lang.Boolean::class.java ->
                Context.toBoolean(v)
            targetType.isInstance(v) -> v
            else -> tryUnbox(v, targetType) ?: v
        }
    }

    /** Kotlin @JvmInline value class 拆箱：若 value 装箱实例的 unbox-impl() 返回类型匹配 targetType，则拆箱。 */
    private fun tryUnbox(value: Any, targetType: Class<*>): Any? = try {
        val m = value.javaClass.getDeclaredMethod("unbox-impl")
        if (targetType.isAssignableFrom(m.returnType)) m.invoke(value) else null
    } catch (_: Exception) { null }

    // ─── 扫描 ────────────────────────────────────────────────

    private fun scanJvmClasspath() {
        scanPackage("org.example.vicky")
        // java.lang is intentionally omitted to avoid replacing JavaScript built-ins.
        listOf(
            "java.io", "java.nio", "java.nio.file", "java.nio.channels", "java.nio.charset",
            "java.util", "java.util.concurrent", "java.util.concurrent.atomic",
            "java.util.concurrent.locks", "java.util.function", "java.util.stream",
            "java.util.regex", "java.math", "java.net", "java.text", "java.time",
            "java.security", "kotlin", "kotlin.annotation", "kotlin.collections",
            "kotlin.comparisons", "kotlin.io", "kotlin.ranges", "kotlin.sequences",
            "kotlin.text", "kotlin.time", "kotlinx.serialization.json", "kotlinx.coroutines",
            "kotlinx.coroutines.sync", "com.aallam.openai.client", "com.aallam.openai.api.model",
            "com.aallam.openai.api.chat", "com.aallam.openai.api.core",
        ).forEach(::scanPackage)
    }

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

    /** 判断类是否应该注册：public、非合成、非匿名、非本地、非编译器生成。 */
    private fun shouldRegister(cls: Class<*>): Boolean =
        cls.simpleName.isNotEmpty() &&
            !cls.simpleName.contains('$') &&
            !cls.name.startsWith("org.example.vicky.platform.") &&
            cls != ScriptRuntimePlatform::class.java &&
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

            val groupDir = if (ScriptRuntimePlatform.isAndroid) {
                null
            } else {
                resolveSkillsDir()?.let { File(it, "runtime-api") }
            }

            // 写入 group.md
            groupDir?.let { dir ->
                val groupMdFile = File(dir, "group.md")
                if (!groupMdFile.exists()) {
                    dir.mkdirs()
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
                groupDir?.let { dir ->
                    val skillDir = File(dir, name)
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
