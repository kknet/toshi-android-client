/*
 * 	Copyright (c) 2017. Toshi Inc
 *
 * 	This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.toshi.view.adapter

import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.ViewGroup
import com.toshi.R
import com.toshi.model.local.Conversation
import com.toshi.model.local.User
import com.toshi.model.sofa.SofaAdapters
import com.toshi.model.sofa.SofaMessage
import com.toshi.model.sofa.SofaType
import com.toshi.util.LogUtil
import com.toshi.view.adapter.viewholder.ConversationRequestViewHolder
import java.io.IOException

class ConversationRequestAdapter(
        private val onItemCLickListener: (Conversation) -> Unit,
        private val onAcceptClickListener: (Conversation) -> Unit,
        private val onRejectClickListener: (Conversation) -> Unit
) : RecyclerView.Adapter<ConversationRequestViewHolder>() {

    private val conversations = mutableListOf<Conversation>()
    var localUser: User? = null

    fun setConversations(conversations: List<Conversation>) {
        this.conversations.clear()
        this.conversations.addAll(conversations)
        this.notifyDataSetChanged()
    }

    fun addConversation(conversation: Conversation) {
        val index = conversations.indexOf(conversation)
        if (index == -1) {
            conversations.add(0, conversation)
            notifyItemInserted(0)
            return
        }

        conversations[index] = conversation
        notifyItemChanged(index)
    }

    fun remove(conversation: Conversation) {
        val index = conversations.indexOf(conversation)
        conversations.remove(conversation)
        notifyItemRemoved(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup?, viewType: Int): ConversationRequestViewHolder {
        val layoutInflater = LayoutInflater.from(parent?.context)
        return ConversationRequestViewHolder(layoutInflater.inflate(R.layout.list_item__conversation_request, parent, false))
    }

    override fun onBindViewHolder(holder: ConversationRequestViewHolder?, position: Int) {
        val conversation = conversations[position]
        val formattedLatestMessage = conversation.latestMessage?.let { formatLastMessage(it) } ?: ""
        holder
                ?.setConversation(conversation)
                ?.setLatestMessage(formattedLatestMessage)
                ?.setOnItemClickListener(conversation, onItemCLickListener)
                ?.setOnAcceptClickListener(conversation, onAcceptClickListener)
                ?.setOnRejectClickListener(conversation, onRejectClickListener)
    }

    private fun formatLastMessage(sofaMessage: SofaMessage): String {
        return try {
            getMessage(sofaMessage)
        } catch (ex: IOException) {
            LogUtil.error(javaClass, "Error parsing SofaMessage. " + ex)
            ""
        }
    }

    private fun getMessage(sofaMessage: SofaMessage): String {
        val sentByLocal = sofaMessage.isSentBy(localUser)
        return when (sofaMessage.type) {
            SofaType.PLAIN_TEXT -> {
                val message = SofaAdapters.get().messageFrom(sofaMessage.payload)
                message.toUserVisibleString(sentByLocal, sofaMessage.hasAttachment())
            }
            SofaType.PAYMENT -> {
                val payment = SofaAdapters.get().paymentFrom(sofaMessage.payload)
                payment.toUserVisibleString(sentByLocal, sofaMessage.sendState)
            }
            SofaType.PAYMENT_REQUEST -> {
                val request = SofaAdapters.get().txRequestFrom(sofaMessage.payload)
                request.toUserVisibleString(sentByLocal, sofaMessage.sendState)
            }
            SofaType.COMMAND_REQUEST, SofaType.INIT_REQUEST, SofaType.INIT, SofaType.UNKNOWN -> return ""
            else -> ""
        }
    }

    override fun getItemCount() = conversations.size
}