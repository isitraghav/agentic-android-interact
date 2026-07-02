package com.agent.nemotron

data class UiElement(
    val id: String,
    val role: String,
    val label: String,
    val y: Int,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val bounds: IntArray
) {
    fun centerX() = (bounds[0] + bounds[2]) / 2f
    fun centerY() = (bounds[1] + bounds[3]) / 2f
}

data class ToolCall(
    val action: String,
    val id: String? = null,
    val text: String? = null,
    val direction: String? = null,
    val pkg: String? = null,
    val ms: Long? = null,
    val phone: String? = null
)

/** role: "user", "assistant" or "status" (ephemeral progress line, not sent back to the LLM) */
data class ChatMessage(
    val role: String,
    val text: String,
    val time: Long = System.currentTimeMillis()
)

/** A single chat session. [title] defaults to a snippet of the first user message. */
data class Conversation(
    val id: String,
    var title: String = "New chat",
    val createdAt: Long = System.currentTimeMillis(),
    val messages: MutableList<ChatMessage> = mutableListOf()
)
