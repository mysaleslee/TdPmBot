package io.github.nekohasekai.pm.instance

import io.github.nekohasekai.nekolib.core.client.TdException
import io.github.nekohasekai.nekolib.core.client.TdHandler
import io.github.nekohasekai.nekolib.core.utils.deleteDelayIf
import io.github.nekohasekai.nekolib.core.utils.input
import io.github.nekohasekai.nekolib.core.utils.make
import io.github.nekohasekai.nekolib.core.utils.syncDelete
import io.github.nekohasekai.pm.DELETED
import io.github.nekohasekai.pm.MESSAGE_DELETED
import io.github.nekohasekai.pm.database.MessageRecord
import io.github.nekohasekai.pm.database.PmInstance
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or

class DeleteHandler(pmInstance: PmInstance) : TdHandler(), PmInstance by pmInstance {

    override suspend fun onDeleteMessages(chatId: Long, messageIds: LongArray, isPermanent: Boolean, fromCache: Boolean) {

        if (!isPermanent || fromCache) return

        val records = database {

            messages.find { messageRecords.messageId inList messageIds.toList() }.toList()

        }

        if (records.isEmpty()) return

        val integration = integration

        val useIntegration = chatId == integration?.integration

        if (chatId == admin || useIntegration) {

            // 主人删除消息 对等删除

            var success = 0
            var failed = 0

            records.filter {

                it.type in arrayOf(
                        MessageRecord.MESSAGE_TYPE_INPUT_FORWARDED,
                        MessageRecord.MESSAGE_TYPE_OUTPUT_MESSAGE
                )

            }.forEach {

                try {

                    database.write {

                        it.delete()

                    }

                    syncDelete(it.chatId, it.targetId!!)

                    success++

                } catch (e: TdException) {

                    failed++

                }

            }

            if (success + failed > 0) {

                sudo make L.DELETED.input(success, success + failed) to chatId send deleteDelayIf(!useIntegration)

            }

        } else {

            // 用户删除消息, 追加提示.

            records.filter {

                it.type in arrayOf(
                        MessageRecord.MESSAGE_TYPE_INPUT_MESSAGE,
                        MessageRecord.MESSAGE_TYPE_OUTPUT_FORWARDED
                )

            }.forEach { record ->

                database.write {

                    record.delete()

                    messages.find {

                        ((messageRecords.type eq MessageRecord.MESSAGE_TYPE_INPUT_FORWARDED) or
                                (messageRecords.type eq MessageRecord.MESSAGE_TYPE_OUTPUT_MESSAGE)) and
                                (messageRecords.targetId eq record.messageId)

                    }.forEach {

                        it.delete()

                        sudo make L.MESSAGE_DELETED replyTo it.messageId sendTo admin

                    }

                }

            }

        }

        finishEvent()

    }

}