package com.syncwatch.ui.watch

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.syncwatch.databinding.ItemUserBinding
import com.syncwatch.model.UserInfo

class UserListAdapter(
    private val mySocketId: String,
    private val isHost: Boolean,
    private val onTransferHost: (targetSocketId: String) -> Unit
) : ListAdapter<UserInfo, UserListAdapter.ViewHolder>(DIFF) {

    inner class ViewHolder(
        private val binding: ItemUserBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(user: UserInfo) {
            val isMe = user.socketId == mySocketId

            binding.tvNickname.text = buildString {
                append(user.nickname)
                if (isMe) append(" (you)")
                if (user.isHost) append(" ★")
            }

            // Only the host can see the "Make Host" button, and only on other users
            binding.btnMakeHost.visibility =
                if (isHost && !isMe && !user.isHost) android.view.View.VISIBLE
                else android.view.View.GONE

            binding.btnMakeHost.setOnClickListener {
                onTransferHost(user.socketId)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemUserBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<UserInfo>() {
            override fun areItemsTheSame(old: UserInfo, new: UserInfo) =
                old.socketId == new.socketId
            override fun areContentsTheSame(old: UserInfo, new: UserInfo) =
                old == new
        }
    }
}
