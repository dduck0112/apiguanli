package com.apiguanli.app.chat

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class MsgMeta(
    /** Model name the server reported back (may differ from the one requested). */
    val model: String? = null,
    val promptTokens: Long? = null,
    val completionTokens: Long? = null,
    val reasoningTokens: Long? = null,
    /** Time to first token, ms. */
    val ttftMs: Long? = null,
    val totalMs: Long = 0,
    val finishReason: String? = null,
)

@Serializable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    /** "user" or "assistant". */
    val role: String,
    val content: String,
    val reasoning: String = "",
    val meta: MsgMeta? = null,
    val error: String? = null,
    /** Model requested for this reply, and the site it went to. */
    val model: String = "",
    val siteName: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
data class ChatSettings(
    val systemPrompt: String = "",
    /** null = let the server decide. */
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val stream: Boolean = true,
)

@Serializable
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "新对话",
    val siteId: String,
    val keyId: String,
    val model: String,
    val settings: ChatSettings = ChatSettings(),
    val messages: List<ChatMessage> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Conversations kept in memory and saved to one private JSON file. */
object ChatStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    // One writer thread so saves land in the order they were requested.
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private lateinit var file: File
    private val _items = MutableStateFlow<List<Conversation>>(emptyList())
    /** Most recently updated first. */
    val items: StateFlow<List<Conversation>> = _items

    fun init(context: Context) {
        if (::file.isInitialized) return
        file = File(context.filesDir, "chats.json")
        _items.value = runCatching { json.decodeFromString<List<Conversation>>(file.readText()) }
            .getOrDefault(emptyList()).sortedByDescending { it.updatedAt }
            .map { c ->
                // A reply that was streaming when the app was killed never got its final state.
                c.copy(messages = c.messages.map { m ->
                    if (m.role == "assistant" && m.meta == null && m.error == null) m.copy(error = "已中断") else m
                })
            }
    }

    fun get(id: String): Conversation? = _items.value.firstOrNull { it.id == id }

    /** Replaces (or inserts) a conversation. [persist] = false for high-frequency streaming updates. */
    @Synchronized
    fun put(conv: Conversation, persist: Boolean = true) {
        val rest = _items.value.filterNot { it.id == conv.id }
        _items.value = (listOf(conv) + rest).sortedByDescending { it.updatedAt }
        if (persist) save()
    }

    @Synchronized
    fun update(id: String, persist: Boolean = true, change: (Conversation) -> Conversation) {
        get(id)?.let { put(change(it), persist) }
    }

    @Synchronized
    fun delete(id: String) {
        _items.value = _items.value.filterNot { it.id == id }
        save()
    }

    private fun save() {
        val snapshot = _items.value
        ioScope.launch {
            val tmp = File(file.parentFile, "chats.json.tmp")
            tmp.writeText(json.encodeToString(snapshot))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }
}
