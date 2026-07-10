package org.example.vicky.session

import com.aallam.openai.api.chat.ChatMessage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * File-backed session storage that works on both desktop JVM and Android.
 * Android callers should place [dataDir] under their app-private files directory.
 */
class JsonSessionStore(
    dataDir: File,
    fileName: String = "sessions.json",
) : SessionStore {

    private val storeFile = File(dataDir, fileName)
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private var loaded = false
    private val conversations = LinkedHashMap<String, StoredConversation>()

    override suspend fun loadHistory(conversationId: String): List<ChatMessage> = io {
        loadIfNeeded()
        conversations[conversationId]?.history.orEmpty().map { it.toMsg() }
    }

    override suspend fun saveHistory(conversationId: String, history: List<ChatMessage>) = io {
        loadIfNeeded()
        conversations[conversationId] = StoredConversation(
            id = conversationId,
            history = history.map { it.toDto() },
            updatedAt = System.currentTimeMillis(),
        )
        persist()
    }

    override suspend fun deleteHistory(conversationId: String) = io {
        loadIfNeeded()
        if (conversations.remove(conversationId) != null) persist()
    }

    override suspend fun listConversations(): List<String> = io {
        loadIfNeeded()
        conversations.values.sortedByDescending { it.updatedAt }.map { it.id }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { block() }
    }

    private fun loadIfNeeded() {
        if (loaded) return
        loaded = true
        if (!storeFile.isFile) return
        val stored = runCatching { json.decodeFromString<StoredSessionFile>(storeFile.readText(Charsets.UTF_8)) }
            .getOrElse { error("Cannot read session store '${storeFile.absolutePath}': ${it.message}") }
        stored.conversations.forEach { conversations[it.id] = it }
    }

    private fun persist() {
        storeFile.parentFile?.mkdirs()
        val temporary = File(storeFile.parentFile, "${storeFile.name}.tmp")
        temporary.writeText(
            json.encodeToString(StoredSessionFile(conversations.values.toList())),
            Charsets.UTF_8,
        )
        if (storeFile.exists() && !storeFile.delete()) {
            temporary.delete()
            error("Cannot replace session store '${storeFile.absolutePath}'")
        }
        if (!temporary.renameTo(storeFile)) {
            temporary.copyTo(storeFile, overwrite = true)
            temporary.delete()
        }
    }
}

@Serializable
private data class StoredSessionFile(
    val conversations: List<StoredConversation> = emptyList(),
)

@Serializable
private data class StoredConversation(
    val id: String,
    val history: List<MsgDto>,
    val updatedAt: Long,
)
