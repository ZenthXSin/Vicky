package org.example.vicky.examples

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.example.vicky.annotations.ToolGroup
import org.example.vicky.annotations.ToolParam
import org.example.vicky.annotations.VickyTool
import org.example.vicky.tool.ToolResult
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
private val client = HttpClient.newHttpClient()

class MindustryMITClient(private val url: String) {
    private var ws: WebSocket? = null
    private val pending = ConcurrentHashMap<String, CompletableFuture<String>>()
    private val counter = AtomicInteger(0)

    fun connect(): Boolean {
        if (ws != null) return true
        val listener = object : WebSocket.Listener {
            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
                ws = webSocket
                val text = data.toString()
                val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                if (root != null) {
                    val wsType = root["wsType"]?.jsonPrimitive?.content.orEmpty()
                    val future = pending.remove(wsType)
                    if (future != null) future.complete(text)
                }
                return CompletableFuture.completedFuture(null)
            }

            override fun onPing(webSocket: WebSocket, message: ByteBuffer): CompletionStage<*> {
                webSocket.sendPong(message)
                return CompletableFuture.completedFuture(null)
            }

            override fun onError(webSocket: WebSocket, error: Throwable) {
                pending.values.forEach { it.completeExceptionally(error) }
                pending.clear()
            }

            override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*> {
                pending.values.forEach { it.completeExceptionally(RuntimeException("Connection closed: $reason")) }
                pending.clear()
                return CompletableFuture.completedFuture(null)
            }
        }
        return try {
            ws = client.newWebSocketBuilder().buildAsync(URI.create(url), listener).get(5, TimeUnit.SECONDS)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun send(wsType: String, content: String = ""): String {
        val conn = ws ?: throw IllegalStateException("Not connected")
        val future = CompletableFuture<String>()
        pending[wsType] = future
        val msg = buildJsonObject {
            put("wsType", JsonPrimitive(wsType))
            if (content.isNotEmpty()) put("content", JsonPrimitive(content))
        }
        conn.sendText(msg.toString(), true)
        return future.get(30, TimeUnit.SECONDS)
    }

    fun disconnect() {
        ws?.sendClose(1000, "bye")
        ws = null
        pending.clear()
    }
}

@ToolGroup(name = "mindustry")
object MindustryMITToolImpl {

    @VickyTool(name = "mindustry_connect", description = "Connect to the MindustryMIT backend WebSocket server at ws://127.0.0.1:19190. Must be called before any other mindustry tools.")
    fun connect(
        @ToolParam(description = "WebSocket URL, default ws://127.0.0.1:19190", required = false) url: String = "ws://127.0.0.1:19190",
    ): ToolResult {
        val c = MindustryMITClient(url)
        return if (c.connect()) {
            conn = c
            ToolResult(toAgent = "Connected to MindustryMIT at $url. Call mindustry_init to load data.")
        } else {
            ToolResult(toAgent = "Error: could not connect to MindustryMIT at $url. Is the server running?")
        }
    }

    @VickyTool(name = "mindustry_init", description = "Initialize the backend: load docs and refresh static instance cache.")
    fun init(
        @ToolParam(description = "Data directory path (e.g. 'mindustry_docs').") dataDir: String,
    ): ToolResult = call("Init", buildJsonObject { put("Data_Dir", JsonPrimitive(dataDir)) })

    @VickyTool(name = "mindustry_list_classes", description = "List all available class names, optionally filtered by parent class (e.g. 'Block', 'UnitType').")
    fun listClasses(
        @ToolParam(description = "Optional parent class name (e.g. 'Block', 'Item').", required = false) parentClass: String = "",
    ): ToolResult = call("AllClass", if (parentClass.isNotBlank()) buildJsonObject { put("Parent_Class", JsonPrimitive(parentClass)) } else null)

    @VickyTool(name = "mindustry_list_fields", description = "List all editable fields of a class (e.g. 'Block', 'UnitType').")
    fun listFields(
        @ToolParam(description = "Class name (e.g. 'Block', 'UnitType', 'Item').") className: String,
    ): ToolResult = call("AllField", buildJsonObject { put("Class_Name", JsonPrimitive(className)) })

    @VickyTool(name = "mindustry_new_class", description = "Create a new editable class instance. Returns classId for subsequent operations.")
    fun newClass(
        @ToolParam(description = "Class name (e.g. 'Block', 'UnitType', 'ItemStack').") className: String,
    ): ToolResult = call("NewClass", buildJsonObject { put("Class_Name", JsonPrimitive(className)) })

    @VickyTool(name = "mindustry_set_field", description = "Set a field value on a class instance.")
    fun setField(
        @ToolParam(description = "Class instance id.") classId: Int,
        @ToolParam(description = "Field path as JSON array string, e.g. '[\"health\"]' or '[\"requirements\",\"#0\",\"amount\"]'.") fieldPath: String,
        @ToolParam(description = "New value string (e.g. '500', 'true', 'copper').", required = false) value: String = "",
        @ToolParam(description = "Another class instance id to set as value (for complex fields).", required = false) valueClassId: Int = -1,
    ): ToolResult {
        val pathArr = try { json.parseToJsonElement(fieldPath).jsonArray } catch (e: Exception) { return ToolResult(toAgent = "Error: fieldPath must be a JSON array, e.g. '[\"health\"]'.") }
        return call("SetFieldValue", buildJsonObject {
            put("Class_Id", JsonPrimitive(classId))
            put("Field_Path", pathArr)
            if (valueClassId >= 0) put("Value_Class_Id", JsonPrimitive(valueClassId))
            if (value.isNotBlank()) put("Value", JsonPrimitive(value))
        })
    }

    @VickyTool(name = "mindustry_add_element", description = "Add an element to an array/list field (e.g. requirements, weapons).")
    fun addElement(
        @ToolParam(description = "Class instance id.") classId: Int,
        @ToolParam(description = "Field path to array, e.g. '[\"requirements\"]'.") fieldPath: String,
        @ToolParam(description = "Element type class name (e.g. 'ItemStack'). Leave empty to auto-detect.") elementType: String,
        @ToolParam(description = "Optional value for leaf elements.", required = false) value: String = "",
    ): ToolResult {
        val pathArr = try { json.parseToJsonElement(fieldPath).jsonArray } catch (e: Exception) { return ToolResult(toAgent = "Error: fieldPath must be a JSON array.") }
        return call("AddElement", buildJsonObject {
            put("Class_Id", JsonPrimitive(classId))
            put("Field_Path", pathArr)
            put("Element_Type", JsonPrimitive(elementType))
            put("Value", JsonPrimitive(value))
        })
    }

    @VickyTool(name = "mindustry_export", description = "Export a class instance as final Mod JSON content.")
    fun export(
        @ToolParam(description = "Class instance id.") classId: Int,
    ): ToolResult = call("ExportClass", buildJsonObject { put("Class_Id", JsonPrimitive(classId)) })

    @VickyTool(name = "mindustry_remove", description = "Remove/delete a class instance from the backend.")
    fun remove(
        @ToolParam(description = "Class instance id.") classId: Int,
    ): ToolResult = call("RemoveClass", buildJsonObject { put("Class_Id", JsonPrimitive(classId)) })

    @VickyTool(name = "mindustry_instances", description = "List static content instances for a class (e.g. 'Block' -> 'Blocks.copperWall').")
    fun listInstances(
        @ToolParam(description = "Class name (e.g. 'Block', 'Item', 'UnitType').") className: String,
    ): ToolResult = call("ClassInstance", buildJsonObject { put("Class_Name", JsonPrimitive(className)) })

    @VickyTool(name = "mindustry_field_doc", description = "Get documentation for a specific field of a class.")
    fun fieldDoc(
        @ToolParam(description = "Class name (e.g. 'Block').") className: String,
        @ToolParam(description = "Field name (e.g. 'health').") fieldName: String,
    ): ToolResult = call("FieldDoc", buildJsonObject {
        put("Class_Name", JsonPrimitive(className))
        put("Field_Name", JsonPrimitive(fieldName))
    })

    @VickyTool(name = "mindustry_fetch_doc", description = "Fetch documentation from Mindustry Wiki and save to data dir.")
    fun fetchDoc(
        @ToolParam(description = "Data directory path.") dataDir: String,
    ): ToolResult = call("FetchDoc", buildJsonObject { put("Data_Dir", JsonPrimitive(dataDir)) })

    @VickyTool(name = "mindustry_disconnect", description = "Disconnect from the MindustryMIT backend.")
    fun disconnect(): ToolResult {
        conn?.disconnect()
        conn = null
        return ToolResult(toAgent = "Disconnected from MindustryMIT.")
    }

    // ─── internal ─────────────────────────────────────────────

    private var conn: MindustryMITClient? = null

    private fun call(wsType: String, params: JsonObject?): ToolResult {
        val c = conn ?: return ToolResult(toAgent = "Error: not connected. Call mindustry_connect first.")
        return try {
            val reqContent = if (params != null) params.toString() else ""
            val resp = c.send(wsType, reqContent)
            ToolResult(toAgent = resp)
        } catch (e: Exception) {
            ToolResult(toAgent = "Error: ${e.message}")
        }
    }
}
