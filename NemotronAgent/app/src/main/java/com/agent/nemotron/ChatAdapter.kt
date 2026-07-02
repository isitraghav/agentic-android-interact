package com.agent.nemotron

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChatAdapter : RecyclerView.Adapter<ChatAdapter.VH>() {

    private val items = mutableListOf<ChatMessage>()

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.messageText)
    }

    override fun getItemViewType(position: Int): Int = when (items[position].role) {
        "user" -> TYPE_USER
        "status" -> TYPE_STATUS
        else -> TYPE_ASSISTANT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = when (viewType) {
            TYPE_USER -> R.layout.item_chat_user
            TYPE_STATUS -> R.layout.item_chat_status
            else -> R.layout.item_chat_assistant
        }
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.text.text = items[position].text
    }

    override fun getItemCount() = items.size

    fun setItems(messages: List<ChatMessage>) {
        items.clear()
        items.addAll(messages)
        notifyDataSetChanged()
    }

    fun append(message: ChatMessage): Int {
        items.add(message)
        notifyItemInserted(items.size - 1)
        return items.size - 1
    }

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }

    companion object {
        private const val TYPE_USER = 0
        private const val TYPE_ASSISTANT = 1
        private const val TYPE_STATUS = 2
    }
}
