package com.agent.nemotron

import android.util.Log
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

/**
 * Persists multiple chat conversations to disk. A brand-new conversation is created every
 * time the store is constructed (i.e. every app launch); past conversations remain available
 * via [all] and [switchTo].
 */
class ConversationStore(private val file: File) {

    private val TAG = "ConversationStore"
    private val gson = GsonBuilder().create()
    private val listType = object : TypeToken<MutableList<Conversation>>() {}.type

    private val conversations: MutableList<Conversation> = load()
    var current: Conversation = createConversation()
        private set

    private fun load(): MutableList<Conversation> = try {
        if (file.exists()) gson.fromJson<MutableList<Conversation>>(file.readText(), listType) ?: mutableListOf()
        else mutableListOf()
    } catch (e: Exception) { Log.e(TAG, "Load failed", e); mutableListOf() }

    private fun persist() {
        try { file.writeText(gson.toJson(conversations)) } catch (e: Exception) { Log.e(TAG, "Save failed", e) }
    }

    /** All conversations, most recently created first. */
    fun all(): List<Conversation> = conversations.sortedByDescending { it.createdAt }

    private fun createConversation(): Conversation {
        val convo = Conversation(id = UUID.randomUUID().toString())
        conversations.add(0, convo)
        if (conversations.size > MAX_CONVERSATIONS) {
            conversations.removeAt(conversations.size - 1)
        }
        persist()
        return convo
    }

    /** Starts and switches to a brand-new conversation. */
    fun newConversation(): Conversation {
        current = createConversation()
        return current
    }

    /** Switches the current conversation to [id], if it exists. */
    fun switchTo(id: String): Conversation {
        conversations.find { it.id == id }?.let { current = it }
        return current
    }

    fun add(message: ChatMessage) {
        current.messages.add(message)
        if (current.messages.size > MAX_MESSAGES) current.messages.removeAt(0)
        if (current.title == "New chat" && message.role == "user") {
            current.title = message.text.take(40).let { if (message.text.length > 40) "$it…" else it }
        }
        persist()
    }

    /** Last [n] non-status messages from the current conversation, oldest first — used as LLM context. */
    fun recentForPrompt(n: Int): List<ChatMessage> =
        current.messages.filter { it.role == "user" || it.role == "assistant" }.takeLast(n)

    /** Keyword search across ALL conversations. Returns formatted snippets, empty string if nothing found. */
    fun search(query: String, limit: Int = 12): String {
        val q = query.lowercase().trim()
        if (q.isBlank()) return ""
        val hits = mutableListOf<String>()
        for (convo in all()) {
            for (msg in convo.messages) {
                if ((msg.role == "user" || msg.role == "assistant") && msg.text.lowercase().contains(q)) {
                    hits.add("[${msg.role}] ${msg.text.take(300)}")
                    if (hits.size >= limit) break
                }
            }
            if (hits.size >= limit) break
        }
        return hits.joinToString("\n")
    }

    companion object {
        private const val MAX_MESSAGES = 200
        private const val MAX_CONVERSATIONS = 50
    }
}
