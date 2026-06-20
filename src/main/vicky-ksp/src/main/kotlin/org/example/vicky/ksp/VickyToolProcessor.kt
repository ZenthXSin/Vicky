package org.example.vicky.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo
import kotlinx.serialization.json.JsonObject

/**
 * VickyTool 注解处理器。
 *
 * 它扫描所有被 [org.example.vicky.annotations.VickyTool] 注解标注的 Kotlin 函数，
 * 然后为每个函数生成一个对应的 [org.example.vicky.tool.Tool] 子类，把：
 *
 *   - `@VickyTool(name, description)` 映射到 `Tool.name` / `Tool.description`
 *   - 函数参数（结合 `@ToolParam`）映射到 OpenAI function-calling 风格的 `parameters` JSON Schema
 *   - `Tool.execute(userId, args)` 实现为：从 `args` 中按类型反序列化参数 → 调用原函数 → 包装返回值为 `ToolResult`
 *
 * 设计要点：
 *   - 仅支持位于 `object` 中的函数（生成代码用 `Parent.func(...)` 直接调用，无需实例化）。
 *   - 函数若声明名为 `userId: String` 的参数，会被识别为"注入参数"：不进入 args/Schema，
 *     而是直接接收 [Tool.execute] 的 `userId`，便于工具内做权限判断。
 *   - 返回值若已是 [ToolResult] 则直接 return；否则包装为 `ToolResult(toAgent = result.toString())`。
 *   - 可选参数（`required = false` 或带 Kotlin 默认值）：生成 nullable 局部变量 + if 分支调用，
 *     在参数缺失时省略命名参数，让原函数自身的默认值生效。
 */

/** 生成代码中复用的常量与符号引用，集中存放方便维护。 */
private object Gen {
    /** 生成类所在的包名，所有 *_Generated 都放在这里。 */
    const val PKG = "org.example.vicky.generated"

    /** 自动注入参数的名字：函数声明 `userId: String` 时会被框架直接注入。 */
    const val USER_ID = "userId"

    /** 生成 execute 方法中接收 JsonObject 的形参名。 */
    const val ARGS = "args"

    /** 运行时 Tool 抽象类。 */
    val TOOL = ClassName("org.example.vicky.tool", "Tool")

    /** Tool 调用结果。 */
    val TOOL_RESULT = ClassName("org.example.vicky.tool", "ToolResult")

    // 下列 MemberName 是 kotlinx.serialization.json 中的 top-level helper，
    // KotlinPoet 会按需自动 import，避免硬编码 import 列表。
    val BUILD_JSON_OBJECT = MemberName("kotlinx.serialization.json", "buildJsonObject")
    val PUT_JSON_OBJECT = MemberName("kotlinx.serialization.json", "putJsonObject")
    val PUT_JSON_ARRAY = MemberName("kotlinx.serialization.json", "putJsonArray")
    val JSON_PRIMITIVE = MemberName("kotlinx.serialization.json", "jsonPrimitive")
    val JSON_PRIMITIVE_CTOR = MemberName("kotlinx.serialization.json", "JsonPrimitive", isExtension = false)
}

/** 从 `@VickyTool` 注解 + 函数名解析出来的元数据。 */
private data class ToolMeta(
    /** `@VickyTool(name = ...)`，会写入 `Tool.name`。 */
    val toolName: String,
    /** `@VickyTool(description = ...)`，会写入 `Tool.description`。 */
    val description: String,
    /** 生成类的名字，例如 `PingTool_Generated`。 */
    val className: String,
)

/** 一个 ToolGroup 下所有工具的信息。 */
private data class GroupEntry(val groupName: String, val toolClassNames: MutableList<String>)

/** 一个函数参数在生成期需要的全部信息。 */
private data class ParamInfo(
    /** Kotlin 形参名，同时也是 JSON Schema 的 property key。 */
    val name: String,
    /** Kotlin 类型（带可空标记），用于决定如何从 JsonObject 反序列化。 */
    val ktType: TypeName,
    /** 映射到 JSON Schema 的类型字符串：`string` / `integer` / `number` / `boolean` / `array`。 */
    val jsonType: String,
    /** `@ToolParam(description = ...)`，写入 Schema properties.<name>.description。 */
    val description: String,
    /** `@ToolParam(required = ...)`，默认 true。注意这只是"注解上声明的"，最终是否必填见 [isOptional]。 */
    val annotatedRequired: Boolean,
    /** 该形参在源码里是否带 Kotlin 默认值（`port: Int = 80`）。 */
    val hasDefault: Boolean,
    /** 是否是 `userId: String` 这种自动注入参数（不进 args、不进 Schema）。 */
    val isUserIdInjection: Boolean,
) {
    /** 只要"声明了非必填"或"有 Kotlin 默认值"，就视为可选 —— 生成代码会走分支调用以保留默认值。 */
    val isOptional: Boolean get() = hasDefault || !annotatedRequired
}

class VickyToolProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        // 1) 找到本轮所有带 @VickyTool 的符号；KSP 多轮处理时 invalid 项后续轮会重试，这里只处理已就绪的。
        val symbols = resolver.getSymbolsWithAnnotation("org.example.vicky.annotations.VickyTool")
        val (valid, invalid) = symbols.partition { it is KSFunctionDeclaration && it.validate() }
        invalid.forEach { logger.error("Invalid @VickyTool element", it) }

        // 2) 对每个有效函数生成一个 Tool 子类，并记录所属 object 和 group。
        val groupEntries = mutableMapOf<String, GroupEntry>() // object全限定名 -> GroupEntry

        valid.filterIsInstance<KSFunctionDeclaration>().forEach { func ->
            val generatedClassName = generateToolClass(func)
            if (generatedClassName != null) {
                val parent = func.parentDeclaration as? KSClassDeclaration
                val parentFqn = parent?.qualifiedName?.asString() ?: return@forEach
                val entry = groupEntries.getOrPut(parentFqn) {
                    val groupName = parent.annotations
                        .firstOrNull { it.shortName.asString() == "ToolGroup" }
                        ?.arguments?.find { it.name?.asString() == "name" }
                        ?.value as? String ?: ""
                    GroupEntry(groupName, mutableListOf())
                }
                entry.toolClassNames.add(generatedClassName)
            }
        }

        // 3) 生成全局 ToolRegistry.tools() 函数。
        val allGroups = groupEntries.values.filter { it.groupName.isNotEmpty() }
        if (allGroups.isNotEmpty()) {
            generateToolRegistry(allGroups)
        }

        return emptyList()
    }

    /** 单个函数 → 一个 *_Generated 工具类的入口。返回生成的类名，失败返回 null。 */
    private fun generateToolClass(func: KSFunctionDeclaration): String? {
        // 约束：必须放在 object 里，方便我们用 Parent.func(...) 这种静态形式调用。
        val parent = func.parentDeclaration as? KSClassDeclaration
        if (parent?.classKind != ClassKind.OBJECT) {
            logger.error("@VickyTool must be defined in an object", func)
            return null
        }
        val meta = parseToolMeta(func) ?: return null
        val params = func.parameters.mapNotNull(::toParamInfo)

        val typeSpec = TypeSpec.classBuilder(meta.className)
            .superclass(Gen.TOOL)
            .addModifiers(KModifier.OPEN)
            .addProperty(stringOverride("name", meta.toolName))
            .addProperty(stringOverride("description", meta.description))
            .addProperty(buildParametersProperty(params))
            .addFunction(buildExecuteFun(func, parent, params))
            .build()

        FileSpec.builder(Gen.PKG, meta.className)
            .addType(typeSpec)
            .build()
            .writeTo(codeGenerator, aggregating = false)

        return meta.className
    }

    /** 生成全局 ToolRegistry 对象，包含 tools(group) 函数。 */
    private fun generateToolRegistry(groups: List<GroupEntry>) {
        val toolType = ClassName("kotlin.collections", "List").parameterizedBy(Gen.TOOL)

        val allToolTypes = groups.flatMap { it.toolClassNames }.map { ClassName(Gen.PKG, it) }
        val allToolInstances = allToolTypes.joinToString(", ") { "%T()" }

        val groupParam = ParameterSpec.builder("group", String::class)
            .defaultValue("%S", "")
            .build()

        // 构建完整的函数体
        val code = CodeBlock.builder()
        code.add("return if (group.isEmpty()) {\n")
        code.add("    listOf($allToolInstances)\n", *allToolTypes.toTypedArray())
        code.add("} else {\n")
        code.add("    when (group) {\n")
        groups.forEach { entry ->
            val toolTypes = entry.toolClassNames.map { ClassName(Gen.PKG, it) }
            val toolInstances = toolTypes.joinToString(", ") { "%T()" }
            code.add("        %S -> listOf($toolInstances)\n", entry.groupName, *toolTypes.toTypedArray())
        }
        code.add("        else -> emptyList()\n")
        code.add("    }\n")
        code.add("}\n")

        val funSpec = FunSpec.builder("tools")
            .addParameter(groupParam)
            .returns(toolType)
            .addCode(code.build())
            .build()

        val registryType = TypeSpec.objectBuilder("ToolRegistry")
            .addFunction(funSpec)
            .build()

        FileSpec.builder(Gen.PKG, "ToolRegistry")
            .addType(registryType)
            .build()
            .writeTo(codeGenerator, aggregating = false)
    }

    /** 读 `@VickyTool` 上的两个字段，并由函数名派生生成类名。 */
    private fun parseToolMeta(func: KSFunctionDeclaration): ToolMeta? {
        val ann = func.annotations.firstOrNull { it.shortName.asString() == "VickyTool" } ?: return null
        val name = ann.arguments.find { it.name?.asString() == "name" }?.value as? String ?: return null
        val desc = ann.arguments.find { it.name?.asString() == "description" }?.value as? String ?: ""
        val className = func.simpleName.asString().replaceFirstChar { it.uppercase() } + "Tool_Generated"
        return ToolMeta(name, desc, className)
    }

    /** 读单个形参的元信息；自动识别 `userId: String` 为注入参数。 */
    private fun toParamInfo(param: KSValueParameter): ParamInfo? {
        val name = param.name?.asString() ?: return null
        val ktType = param.type.resolve().toTypeName()
        val ann = param.annotations.firstOrNull { it.shortName.asString() == "ToolParam" }
        val description = ann?.arguments?.find { it.name?.asString() == "description" }?.value as? String ?: ""
        val required = ann?.arguments?.find { it.name?.asString() == "required" }?.value as? Boolean ?: true
        // 注入参数的判定：名字是 userId 且类型是非空 String。可空 String? 不视作注入，避免歧义。
        val isUserId = name == Gen.USER_ID && ktType.toString().removeSuffix("?") == "kotlin.String"
        return ParamInfo(
            name = name,
            ktType = ktType,
            jsonType = mapKotlinTypeToJson(ktType),
            description = description,
            annotatedRequired = required,
            hasDefault = param.hasDefault,
            isUserIdInjection = isUserId,
        )
    }

    /** 生成形如 `override val name: String = "ping"` 的属性。 */
    private fun stringOverride(name: String, value: String): PropertySpec =
        PropertySpec.builder(name, String::class, KModifier.OVERRIDE)
            .initializer("%S", value)
            .build()

    /**
     * 生成 `parameters: JsonObject` 属性，输出 OpenAI function-calling 风格的 JSON Schema：
     *
     * ```
     * { "type": "object",
     *   "properties": { "<p>": { "type": ..., "description": ... }, ... },
     *   "required": [ ... ] }   // 只有存在必填字段时才出现
     * ```
     *
     * userId 注入参数会被跳过，因为它由框架直接提供，不需要 AI 给值。
     */
    private fun buildParametersProperty(params: List<ParamInfo>): PropertySpec {
        val schemaParams = params.filterNot { it.isUserIdInjection }
        val requiredNames = schemaParams.filter { !it.isOptional }.map { it.name }
        val code = CodeBlock.builder()
            .add("%M {\n", Gen.BUILD_JSON_OBJECT).indent()
            .add("put(%S, %M(%S))\n", "type", Gen.JSON_PRIMITIVE_CTOR, "object")
            .add("%M(%S) {\n", Gen.PUT_JSON_OBJECT, "properties").indent()
        schemaParams.forEach { p ->
            code.add("%M(%S) {\n", Gen.PUT_JSON_OBJECT, p.name).indent()
                .add("put(%S, %M(%S))\n", "type", Gen.JSON_PRIMITIVE_CTOR, p.jsonType)
                .add("put(%S, %M(%S))\n", "description", Gen.JSON_PRIMITIVE_CTOR, p.description)
                .unindent().add("}\n")
        }
        code.unindent().add("}\n")
        if (requiredNames.isNotEmpty()) {
            code.add("%M(%S) {\n", Gen.PUT_JSON_ARRAY, "required").indent()
            requiredNames.forEach { code.add("add(%M(%S))\n", Gen.JSON_PRIMITIVE_CTOR, it) }
            code.unindent().add("}\n")
        }
        code.unindent().add("}")

        return PropertySpec.builder("parameters", JsonObject::class.asClassName(), KModifier.OVERRIDE)
            .initializer(code.build())
            .build()
    }

    /**
     * 生成 `override suspend fun execute(userId, args): ToolResult`。
     *
     * 函数体分三段：
     *  1. 必填参数：`val x = args[..].jsonPrimitive.content ?: return ToolResult(error)`
     *  2. 可选参数：`val x = args[..].jsonPrimitive.content` （nullable）
     *  3. 调用原函数 + 返回：见 [buildCallAndReturn]
     */
    private fun buildExecuteFun(
        func: KSFunctionDeclaration,
        parent: KSClassDeclaration,
        params: List<ParamInfo>,
    ): FunSpec {
        val builder = FunSpec.builder("execute")
            .addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
            .addParameter(Gen.USER_ID, String::class)
            .addParameter(Gen.ARGS, JsonObject::class.asClassName())
            .returns(Gen.TOOL_RESULT)

        params.forEach { p ->
            when {
                p.isUserIdInjection -> Unit // 注入参数无需从 args 提取，直接在调用处传 userId
                p.isOptional -> builder.addCode(extractOptionalParam(p))
                else -> builder.addCode(extractRequiredParam(p))
            }
        }

        builder.addCode(buildCallAndReturn(func, parent, params))
        return builder.build()
    }

    /** 必填参数：缺失即用 `?:` 提前 return 带错误信息的 ToolResult。 */
    private fun extractRequiredParam(p: ParamInfo): CodeBlock =
        CodeBlock.builder()
            .add("val %N = %L\n", p.name, jsonAccessor(p))
            .add(
                "    ?: return %T(toAgent = %S)\n",
                Gen.TOOL_RESULT,
                "Error: missing required parameter '${p.name}'.",
            )
            .build()

    /** 可选参数：保留为 nullable 局部变量，后续调用处会判 null 决定是否传值。 */
    private fun extractOptionalParam(p: ParamInfo): CodeBlock =
        CodeBlock.builder()
            .add("val %N = %L\n", p.name, jsonAccessor(p))
            .build()

    /**
     * 根据 JSON Schema 类型生成一个 `args["k"]?.jsonPrimitive?.<...>` 表达式。
     * 数字/布尔走 `toIntOrNull` / `toDoubleOrNull` / `toBooleanStrictOrNull`，保证类型不匹配时安静地变 null。
     */
    private fun jsonAccessor(p: ParamInfo): CodeBlock {
        val base = CodeBlock.of("%N[%S]?.%M", Gen.ARGS, p.name, Gen.JSON_PRIMITIVE)
        return when (p.jsonType) {
            "string" -> CodeBlock.of("%L?.content", base)
            "integer" -> CodeBlock.of("%L?.content?.toIntOrNull()", base)
            "number" -> CodeBlock.of("%L?.content?.toDoubleOrNull()", base)
            "boolean" -> CodeBlock.of("%L?.content?.toBooleanStrictOrNull()", base)
            else -> CodeBlock.of("%L?.content", base)
        }
    }

    /**
     * 生成最后的调用与返回。原函数返回 [ToolResult] 时直接 `return`，
     * 否则包成 `ToolResult(toAgent = result.toString())`，保证 execute 一定返回 ToolResult。
     */
    private fun buildCallAndReturn(
        func: KSFunctionDeclaration,
        parent: KSClassDeclaration,
        params: List<ParamInfo>,
    ): CodeBlock {
        val returnsToolResult = func.returnType?.resolve()?.toTypeName() == Gen.TOOL_RESULT
        val callBlock = buildDispatchCall(func.simpleName.asString(), parent.toClassName(), params)
        return if (returnsToolResult) {
            CodeBlock.builder().add("return %L\n", callBlock).build()
        } else {
            CodeBlock.builder()
                .add("val __result = %L\n", callBlock)
                .add("return %T(toAgent = __result.toString())\n", Gen.TOOL_RESULT)
                .build()
        }
    }

    /**
     * 对每个可选参数生成一层 `if (x != null) <带 x 的调用> else <不带 x 的调用>` 嵌套，
     * 让 Kotlin 默认值在 args 缺失时自动生效。
     *
     * 分支总数 = 2^N，其中 N 是可选参数个数。
     * 真实工具一般只有 0~2 个可选参数，4~16 个分支由 KotlinPoet 渲染成一行三元式，可读性可接受。
     */
    private fun buildDispatchCall(
        funcName: String,
        parentClass: ClassName,
        params: List<ParamInfo>,
    ): CodeBlock {
        val optionals = params.filter { it.isOptional && !it.isUserIdInjection }
        return buildCallVariants(funcName, parentClass, params, optionals, presentMask = emptyList())
    }

    /**
     * 递归构造调用表达式：每一层挑一个可选参数，分裂成"传/不传"两种调用形态。
     *
     * @param presentMask 已经决定的可选参数是否传值；长度从 0 增长到 optionals.size。
     */
    private fun buildCallVariants(
        funcName: String,
        parentClass: ClassName,
        params: List<ParamInfo>,
        optionals: List<ParamInfo>,
        presentMask: List<Boolean>,
    ): CodeBlock {
        if (presentMask.size == optionals.size) {
            return buildSingleCall(funcName, parentClass, params, optionals, presentMask)
        }
        val current = optionals[presentMask.size]
        val whenPresent = buildCallVariants(funcName, parentClass, params, optionals, presentMask + true)
        val whenAbsent = buildCallVariants(funcName, parentClass, params, optionals, presentMask + false)
        return CodeBlock.of(
            "(if (%N != null) %L else %L)",
            current.name, whenPresent, whenAbsent,
        )
    }

    /** 根据 presentMask 拼出一次具体的命名参数调用：缺失的可选参数直接省略，让 Kotlin 默认值生效。 */
    private fun buildSingleCall(
        funcName: String,
        parentClass: ClassName,
        params: List<ParamInfo>,
        optionals: List<ParamInfo>,
        presentMask: List<Boolean>,
    ): CodeBlock {
        val presentSet = optionals
            .mapIndexedNotNull { idx, p -> if (presentMask[idx]) p.name else null }
            .toSet()
        val callArgs = params.mapNotNull { p ->
            when {
                p.isUserIdInjection -> "${p.name} = ${Gen.USER_ID}"
                p.isOptional -> if (p.name in presentSet) "${p.name} = ${p.name}" else null
                else -> "${p.name} = ${p.name}"
            }
        }
        return CodeBlock.of("%T.%N(${callArgs.joinToString(", ")})", parentClass, funcName)
    }

    /** Kotlin 类型 → JSON Schema 类型字符串。未识别的类型默认按 string 处理。 */
    private fun mapKotlinTypeToJson(typeName: TypeName): String {
        val raw = typeName.toString().removeSuffix("?")
        return when (raw) {
            "kotlin.String" -> "string"
            "kotlin.Int", "kotlin.Long", "kotlin.Short", "kotlin.Byte" -> "integer"
            "kotlin.Float", "kotlin.Double" -> "number"
            "kotlin.Boolean" -> "boolean"
            else -> if (raw.startsWith("kotlin.collections.List")) "array" else "string"
        }
    }
}
