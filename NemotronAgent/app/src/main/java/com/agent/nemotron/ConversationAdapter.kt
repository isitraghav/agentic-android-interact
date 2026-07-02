package com.agent.nemotron

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ConversationAdapter(private val onClick: (Conversation) -> Unit) :
    RecyclerView.Adapter<ConversationAdapter.VH>() {

    private val items = mutableListOf<Conversation>()

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view as TextView
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_conversation, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val convo = items[position]
        holder.title.text = convo.title
        holder.title.setOnClickListener { onClick(convo) }
    }

    override fun getItemCount() = items.size

    fun setItems(conversations: List<Conversation>) {
        items.clear()
        items.addAll(conversations)
        notifyDataSetChanged()
    }
}
