package io.nekohasekai.pm.manage

import cn.hutool.core.date.DateUtil
import cn.hutool.core.io.FileUtil
import io.nekohasekai.ktlib.compress.tar
import io.nekohasekai.ktlib.compress.writeDirectory
import io.nekohasekai.ktlib.compress.writeFile
import io.nekohasekai.ktlib.compress.xz
import io.nekohasekai.ktlib.td.core.TdClient
import io.nekohasekai.ktlib.td.core.TdHandler
import io.nekohasekai.ktlib.td.utils.UploadingDocument
import io.nekohasekai.ktlib.td.utils.isChatAdmin
import io.nekohasekai.ktlib.td.utils.make
import io.nekohasekai.ktlib.td.utils.makeFile
import io.nekohasekai.pm.database.PmInstance
import kotlinx.coroutines.runBlocking
import td.TdApi
import java.io.File
import java.util.*

class Backup(pmInstance: PmInstance) : TdHandler(), PmInstance by pmInstance {

    override fun onLoad() {
        initFunction("backup")
    }

    override suspend fun onFunction(
        userId: Int,
        chatId: Long,
        message: TdApi.Message,
        function: String,
        param: String,
        params: Array<String>
    ) {
        if (chatId != admin && (chatId != integration?.integration || !isChatAdmin(chatId, userId))) rejectFunction()

        sudo make UploadingDocument sendTo chatId

        val backupTo = File(
            global.cacheDir, "td-pm-${
                global.tag.replace("  ", " ").replace(" ", "-").replace("_", "-")
            }-backup-${DateUtil.format(Date(), "yyyyMMdd-HHmmss")}.tar.xz"
        )

        createBackup(backupTo)

        sudo makeFile backupTo syncTo chatId

        backupTo.delete()

    }

    suspend fun scheduleBackup() {

        val start = System.currentTimeMillis()

        val lastBackup = global.schemes.getItem<Long>("last_backup")
        val lastBackupId = global.schemes.getItem<Long>("last_backup_message_id")

        val backupTo = File(
            global.cacheDir, "td-pm-${
                global.tag.replace("  ", " ").replace(" ", "-").replace("_", "-")
            }-backup.tar.xz"
        )

        createBackup(backupTo)

        runCatching backup@{
            if (lastBackup != null && System.currentTimeMillis() - lastBackup < global.backupOverwrite) runCatching {
                sudo makeFile backupTo at lastBackupId!! syncEditTo global.autoBackup
                return@backup
            }
            val message = sudo makeFile backupTo syncTo global.autoBackup
            global.schemes.setItem("last_backup", start)
            global.schemes.setItem("last_backup_message_id", message.id)
        }.onFailure {
            TdClient.contextErrorHandler(global, it, "auto backup")
        }

    }

}

fun TdHandler.createBackup(backupTo: File) {

    val output = FileUtil.touch(backupTo).outputStream().xz().tar()

    output.writeFile("pm.yml", global.configFile)
    output.writeDirectory("data/")
    output.writeFile("data/pm_data.db", File(global.dataDir, "pm_data.db"))
    output.writeFile("data/td.binlog", File(global.dataDir, "td.binlog"))

    val pmBots = File(global.dataDir, "pm").listFiles()

    if (!pmBots.isNullOrEmpty()) {

        output.writeDirectory("data/pm/")

        pmBots.forEach {
            output.writeDirectory("data/pm/${it.name}/")
            output.writeFile("data/pm/${it.name}/td.binlog", File(it, "td.binlog"))
        }

    }

    output.finish()
    output.close()

}