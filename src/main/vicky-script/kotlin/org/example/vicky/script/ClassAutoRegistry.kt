package org.example.vicky.script

import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Undefined
import java.io.File
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

/**
 * 反射扫描 classpath，自动注入类到 Rhino 全局作用域。
 * 脚本中可直接使用类名，无需 import。
 */
object ClassAutoRegistry {

    private val classMap = ConcurrentHashMap<String, Class<*>>()
    @Volatile private var initialized = false

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
    }

    fun register(cls: Class<*>) {
        classMap.putIfAbsent(cls.simpleName, cls)
    }

    fun register(name: String, cls: Class<*>) {
        classMap[name] = cls
    }

    fun registeredNames(): Set<String> = classMap.keys.toSet()

    private fun injectClass(ctx: Context, scope: ScriptableObject, name: String, cls: Class<*>) {
        try {
            val proxy = createClassProxy(ctx, scope, cls)
            // 枚举类型：将枚举常量注入为属性，如 StandardCopyOption.REPLACE_EXISTING
            if (cls.isEnum) {
                for (constant in cls.enumConstants) {
                    val enumVal = constant as Enum<*>
                    ScriptableObject.putProperty(proxy, enumVal.name, Context.javaToJS(constant, scope))
                }
            }
            ScriptableObject.putProperty(scope, name, proxy)
        } catch (e: Exception) {
            println("[Vicky][script] 注入类 $name 失败: ${e.message}")
        }
    }

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
                // Rhino 原生包装：保留 Java 对象的方法访问能力
                val wrapped = Context.javaToJS(instance, scope as ScriptableObject)
                return wrapped as? Scriptable
                    ?: throw ScriptException("Cannot wrap ${cls.simpleName} as JS object")
            }
        }
    }

    private fun tryCreateInstance(cx: Context, scope: ScriptableObject, cls: Class<*>, args: Array<out Any?>): Any? {
        // 先尝试匹配参数数量的构造函数
        for (ctor in cls.constructors) {
            if (ctor.parameterCount == args.size) {
                try {
                    val converted = convertArgs(cx, scope, ctor.parameterTypes, args)
                    return ctor.newInstance(*converted)
                } catch (_: Exception) { continue }
            }
        }
        // 无参构造
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

    /**
     * 将 Java 对象包装为 Rhino JS 对象，使其属性可在 JS 中访问。
     */
    private fun wrapAsJsObject(cx: Context, scope: ScriptableObject, obj: Any?, cls: Class<*>): Scriptable {
        val jsObj = cx.newObject(scope)
        if (obj == null) return jsObj
        for (field in cls.declaredFields) {
            if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
            try {
                field.isAccessible = true
                val value = field.get(obj)
                ScriptableObject.putProperty(jsObj, field.name, Context.javaToJS(value, scope))
            } catch (_: Exception) {
                // 跳过无法访问的字段（Java module 限制）
            }
        }
        return jsObj
    }

    private fun scanPackage(packageName: String) {
        val classLoader = ClassAutoRegistry::class.java.classLoader ?: return
        val path = packageName.replace('.', '/')
        try {
            val resources = classLoader.getResources(path)
            while (resources.hasMoreElements()) {
                val resource = resources.nextElement()
                val resourcePath = resource.path
                if (resourcePath.startsWith("file:")) {
                    val file = File(URLDecoder.decode(resourcePath.removePrefix("file:"), "UTF-8"))
                    if (file.isDirectory) scanDirectory(file, packageName)
                }
            }
        } catch (_: Exception) {}
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
                    if (cls.simpleName.isNotEmpty() && !cls.isAnonymousClass && !cls.isLocalClass) {
                        val existing = classMap[cls.simpleName]
                        if (existing == null || cls.packageName.startsWith("org.example.vicky")) {
                            classMap[cls.simpleName] = cls
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }
}
