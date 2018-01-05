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

package com.toshi.manager.chat.tasks

import com.toshi.crypto.signal.model.DecryptedSignalMessage
import com.toshi.util.FileUtil
import org.spongycastle.util.encoders.Hex
import org.whispersystems.signalservice.api.SignalServiceMessageReceiver
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentPointer

class ProcessAttachmentsTask(
        private val messageReceiver: SignalServiceMessageReceiver
) {
    fun run(signalMessage: DecryptedSignalMessage) {
        if (!signalMessage.attachments.isPresent) return

        val attachments = signalMessage.attachments?.get() ?: emptyList()
        if (attachments.size > 0) {
            val groupId = Hex.toHexString(signalMessage.group?.groupId)
            val attachment = attachments[0]
            groupId?.let {
                signalMessage.attachmentFilePath = saveAttachmentToFile(attachment.asPointer(), it)
            }
        }
    }

    private fun saveAttachmentToFile(attachment: SignalServiceAttachmentPointer, groupId: String): String? {
        val attachmentFile = FileUtil.writeAttachmentToFileFromMessageReceiver(attachment, messageReceiver, groupId)
        return attachmentFile?.absolutePath
    }
}