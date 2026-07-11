package org.example.vicky.session

import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.FunctionCall
import com.aallam.openai.api.chat.ToolCall
import com.aallam.openai.api.chat.ToolId
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

class SqliteSessionStore(dataDir: File) : SessionStore {

    private val db: Connection = run {
        dataDir.mkdirs()
        DriverManager.getConnection("jdbc:sqlite:${File(dataDir, "sessions.db").absolutePath}").also { conn ->
            conn.createStatement().use { st ->
                st.execute("PRAGMA journal_mode=WAL")
                st.execute("""
                    CREATE TABLE IF NOT EXISTS conversations (
                        id TEXT PRIMARY KEY,
                        history TEXT NOT NULL DEFAULT '[]',
                        updated_at INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }
    }

    override suspend fun loadHistory(conversationId: String): List<ChatMessage> {
        db.prepareStatement("SELECT history FROM conversations WHERE id=?").use { stmt ->
            stmt.setString(1, conversationId)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) return emptyList()
                return Json.decodeFromString<List<MsgDto>>(rs.getString(1)).map { it.toMsg() }
            }
        }
    }

    override suspend fun saveHistory(conversationId: String, history: List<ChatMessage>) {
        val json = Json.encodeToString(history.map { it.toDto() })
        db.prepareStatement(
            "INSERT OR REPLACE INTO conversations(id,history,updated_at) VALUES(?,?,?)"
        ).use { stmt ->
            stmt.setString(1, conversationId)
            stmt.setString(2, json)
            stmt.setLong(3, System.currentTimeMillis())
            stmt.executeUpdate()
        }
    }

    override suspend fun deleteHistory(conversationId: String) {
        db.prepareStatement("DELETE FROM conversations WHERE id=?").use { stmt ->
            stmt.setString(1, conversationId)
            stmt.executeUpdate()
        }
    }

    override suspend fun listConversations(): List<String> {
        db.createStatement().use { st ->
            st.executeQuery("SELECT id FROM conversations ORDER BY updated_at DESC").use { rs ->
                val ids = mutableListOf<String>()
                while (rs.next()) ids += rs.getString(1)
                return ids
            }
        }
    }

    override fun close() { if (!db.isClosed) db.close() }
}

@Serializable
internal data class MsgDto(
    val role: String,
    val content: String? = null,
    val toolCallId: String? = null,
    val name: String? = null,
    val toolCalls: List<ToolCallDto>? = null,
)

@Serializable
internal data class ToolCallDto(val id: String, val name: String?, val args: String?)

internal fun ChatMessage.toDto() = MsgDto(
    role = role.role,
    content = content,
    toolCallId = toolCallId?.id,
    name = name,
    toolCalls = toolCalls?.filterIsInstance<ToolCall.Function>()?.map {
        ToolCallDto(it.id.id, it.function.nameOrNull, it.function.argumentsOrNull)
    },
)

internal fun MsgDto.toMsg() = ChatMessage(
    role = ChatRole(role),
    content = content,
    toolCallId = toolCallId?.let { ToolId(it) },
    name = name,
    toolCalls = toolCalls?.map {
        ToolCall.Function(
            id = ToolId(it.id),
            function = FunctionCall(nameOrNull = it.name, argumentsOrNull = it.args),
        )
    },
)
