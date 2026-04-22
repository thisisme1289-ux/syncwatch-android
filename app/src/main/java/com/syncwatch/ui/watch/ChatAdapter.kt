package com.syncwatch.ui.watch

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.syncwatch.databinding.ItemChatMessageBinding
import com.syncwatch.model.ChatMessage
import com.syncwatch.util.toChatTime

class ChatAdapter(
    private val myNickname: String
) : ListAdapter<ChatMessage, ChatAdapter.ViewHolder>(DIFF) {

    inner class ViewHolder(
        private val binding: ItemChatMessageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(msg: ChatMessage) {
            val isMine = msg.nickname == myNickname
            binding.tvNickname.text = if (isMine) "You" else msg.nickname
            binding.tvMessage.text = msg.text
            binding.tvTime.text = msg.ts.toChatTime()

            // Align own messages to the right; others to the left
            binding.root.layoutDirection =
                if (isMine) android.view.View.LAYOUT_DIRECTION_RTL
                else android.view.View.LAYOUT_DIRECTION_LTR
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemChatMessageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(old: ChatMessage, new: ChatMessage) =
                old.ts == new.ts && old.nickname == new.nickname
            override fun areContentsTheSame(old: ChatMessage, new: ChatMessage) =
                old == new
        }
    }
}
