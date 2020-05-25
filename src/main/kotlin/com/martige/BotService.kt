package com.martige

import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.v2.DbxClientV2
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.martige.model.DatHostGameServer
import com.martige.model.DatHostPathsItem
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.VoiceChannel
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.TimeUnit


class BotService(props: Properties, private var jda: JDA) {
    private var gameServerIp: String = props.getProperty("gameserver.ip")
    private var gameServerId = props.getProperty("gameserver.id")
    private var auth64String = "Basic " + Base64.getEncoder()
        .encodeToString(
            "${props.getProperty("dathost.username")}:${props.getProperty("dathost.password")}"
                .toByteArray()
        )
    private var dmTemplate =
        props.getProperty("dm.template") ?: "Your scrim server is ready! Paste this into your console:"
    private var discordPrivilegeRoleId = props.getProperty("discord.role.privilege.id").toLong()
    private var discordVoiceChannelId = props.getProperty("discord.voicechannel.id").toLong()
    private var discordTextChannelId: Long = props.getProperty("discord.textchannel.id").toLong()
    private val log: Logger = LoggerFactory.getLogger(BotService::class.java)
    private var queue: ArrayList<User> = arrayListOf()
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private var autoclearHour = props.getProperty("bot.autoclear.hourofday") ?: "7"
    private var config: DbxRequestConfig = DbxRequestConfig.newBuilder("dropbox/mert-scrim-bot").build()
    private lateinit var dropboxClient: DbxClientV2
    private var dropboxToken = props.getProperty("dropbox.token")
    private var dropboxDemosFolder = props.getProperty("dropbox.sharedfolder") ?: ""

    fun addToQueue(event: MessageReceivedEvent) {
        if (queue.size == 10) {
            event.channel.sendMessage("10 players are ready! Type !start to launch the scrim server").queue()
        } else {
            if (!queue.contains(event.author)) {
                queue.add(event.author)
                event.channel.sendMessage("<@${event.author.id}> has joined the queue! Queue size: ${queue.size}/10")
                    .queue()
            } else {
                event.channel.sendMessage("<@${event.author.id}> is already in the queue").queue()
            }
        }
    }

    fun removeFromQueue(event: MessageReceivedEvent) {
        if (queue.contains(event.author)) {
            queue.remove(event.author)
            event.channel.sendMessage("<@${event.author.id}> has left the queue, see ya! Queue size ${queue.size}/10")
                .queue()
        } else {
            event.channel.sendMessage("Hey <@${event.author.id}>, you're not in the queue. Type `!join` to queue")
                .queue()
        }
    }

    fun listQueue(event: MessageReceivedEvent) {
        if (queue.size == 0) {
            event.channel.sendMessage("The queue is currently empty").queue()
            return
        }
        val stringBuilder = StringBuilder().appendln("The following users are queued: (${queue.size}/10)")
        queue.forEach { stringBuilder.appendln("- @${it.name}") }
        event.channel.sendMessage(stringBuilder.toString()).queue()
    }

    fun clearQueue(event: MessageReceivedEvent) {
        if (!isMemberPrivileged(event)) {
            event.channel.sendMessage("You do not have the correct role for this command").queue()
            return
        }
        queue.clear()
        event.channel.sendMessage("Queue cleared").queue()
    }

    fun startServer(event: MessageReceivedEvent) {
        val force = event.message.contentRaw.contains(" -force")
        if (!isMemberPrivileged(event) && force) return
        val gameServer = findGameServerById(httpClient, gameServerId) ?: return
        val isEmpty = gameServer.players_online == 0
        if ((queue.size == 10 || force) && isEmpty) {
            event.channel.sendMessage("Starting scrim server...").queue()
        } else {
            if (!isEmpty) event.channel
                .sendMessage("Yo, <@${event.author.id}> I can't start the server because it's not empty")
                .queue()
            else
                event.channel
                    .sendMessage("Yo, <@${event.author.id}> I can't start the server because the queue isn't full")
                    .queue()
            return
        }
        val randomPass = Math.random().toString().replace("0.", "")
        changeGameServerPassword(randomPass).use {
            log.info("Server password change response: ${it.message}")
        }
        stopGameServer().use {
            log.info("Server stop response: ${it.message}")
        }
        startGameServer().use {
            log.info("Server start response: ${it.message}")
        }
        GlobalScope.launch {
            for (x in 0..24) {
                val gameServerPing = findGameServerById(httpClient, gameServerId) ?: return@launch
                if (gameServerPing.booting) {
                    log.info("Server is still booting, waiting 5s...")
                    delay(5000)
                } else {
                    log.info("Server has booted")
                    break
                }
            }
            queue.forEach { user ->
                user.openPrivateChannel()
                    .queue { privateChannel -> privateChannel.sendMessage(generateTemplate(randomPass)).queue() }
            }
            val unconnectedUsers: ArrayList<User> = arrayListOf()
            queue.forEach { user ->
                val channel: VoiceChannel? = event.guild.voiceChannels
                    .firstOrNull { voiceChannel ->
                        voiceChannel.members.firstOrNull { member -> member.user.idLong == user.idLong } != null
                    }
                if (channel != null) {
                    event.guild.moveVoiceMember(
                        event.guild.getMember(user)!!,
                        event.guild.getVoiceChannelById(discordVoiceChannelId)
                    ).queue()
                } else {
                    unconnectedUsers.add(user)
                    user.openPrivateChannel()
                        .queue { privateChannel ->
                            privateChannel.sendMessage(
                                "Please connect to the `${event.guild.name} > ${event.guild.getVoiceChannelById(
                                    discordVoiceChannelId
                                )?.name}` voice channel"
                            ).queue()
                        }
                }
            }
            if (unconnectedUsers.size > 0) {
                val stringBuilder =
                    StringBuilder().appendln("The following queued users are not in the discord and cannot be moved to the default scrim voice channel:")
                unconnectedUsers.forEach { stringBuilder.appendln("- <@${it.id}>") }
                event.channel.sendMessage(stringBuilder.toString()).queue()
            }
            // cleanup
            queue.clear()
            log.info("Startup process has completed successfully")
        }

    }

    fun recoverQueue(event: MessageReceivedEvent) {
        if (!isMemberPrivileged(event)) {
            event.channel.sendMessage("You do not have the correct role for this command").queue()
            return
        }
        queue.clear()
        event.message.mentionedMembers.forEach { queue.add(it.user) }
        event.channel.sendMessage("Successfully recovered queue").queue()
        listQueue(event)
    }

    fun unknownCommand(event: MessageReceivedEvent) {
        if (!event.message.contentRaw.startsWith("!")) return
        event.channel.sendMessage("Unknown command, type `!help` for a list of valid commands").queue()
    }

    fun listCommands(event: MessageReceivedEvent) {
        val stringBuilder = StringBuilder().appendln("List of available commands:")
        Bot.Command.values()
            .filter { it != Bot.Command.UNKNOWN }
            .forEach { stringBuilder.appendln("`${it.command}` - ${it.description}") }
        event.channel.sendMessage(stringBuilder.toString()).queue()
    }

    private fun findGameServerById(httpClient: OkHttpClient, gameServerId: String): DatHostGameServer? {
        val serverStateRequest = Request.Builder()
            .url("https://dathost.net/api/0.1/game-servers")
            .get()
            .header("Authorization", auth64String)
            .build()
        val serverStateResponse = httpClient.newCall(serverStateRequest).execute()
        val responseBody = serverStateResponse.body?.string() ?: return null
        serverStateResponse.close()
        val gameServers = Gson().fromJson<List<DatHostGameServer>>(responseBody)
        return gameServers.first { it.id == gameServerId }
    }

    private fun changeGameServerPassword(password: String): Response {
        val formUrlEncoded: MediaType? = "application/x-www-form-urlencoded; charset=utf-8".toMediaTypeOrNull()
        val changePasswordRequest = Request.Builder()
            .url("https://dathost.net/api/0.1/game-servers/$gameServerId")
            .put("csgo_settings.password=$password".toRequestBody(formUrlEncoded))
            .header("Authorization", auth64String)
            .build()
        return httpClient.newCall(changePasswordRequest).execute()
    }

    private fun stopGameServer(): Response {
        val json: MediaType? = "application/json; charset=utf-8".toMediaTypeOrNull()
        val stopServerRequest = Request.Builder()
            .url("https://dathost.net/api/0.1/game-servers/$gameServerId/stop")
            .post("".toRequestBody(json))
            .header("Authorization", auth64String)
            .build()
        return httpClient.newCall(stopServerRequest).execute()
    }

    private fun startGameServer(): Response {
        val json: MediaType? = "application/json; charset=utf-8".toMediaTypeOrNull()
        val startServerRequest = Request.Builder()
            .url("https://dathost.net/api/0.1/game-servers/$gameServerId/start")
            .post("".toRequestBody(json))
            .header("Authorization", auth64String)
            .build()
        return httpClient.newCall(startServerRequest).execute()
    }

    private fun listGameServerFiles(path: String = ""): Response {
        val listGameServerFilesRequest = Request.Builder()
            .url("https://dathost.net/api/0.1/game-servers/$gameServerId/files")
            .get()
            .header("Authorization", auth64String)
            .addHeader("path", path)
            .build()
        return httpClient.newCall(listGameServerFilesRequest).execute()
    }

    private fun getFile(path: String): Response {
        val getFileRequest = Request.Builder()
            .url("https://dathost.net/api/0.1/game-servers/$gameServerId/files/$path")
            .get()
            .header("Authorization", auth64String)
            .build()
        return httpClient.newCall(getFileRequest).execute()
    }

    private fun generateTemplate(password: String): String {
        return "$dmTemplate\n`connect $gameServerIp;password $password`"
    }

    private fun isMemberPrivileged(event: MessageReceivedEvent): Boolean {
        return event.guild.getMembersWithRoles(event.guild.getRoleById(discordPrivilegeRoleId))
            .contains(event.member)
    }

    fun enableDemoUpload() {
        dropboxClient = DbxClientV2(config, dropboxToken)
        log.info("Started demo upload feature")
        GlobalScope.launch {
            while (true) {
                var rootFiles: List<DatHostPathsItem> = listOf()
                listGameServerFiles().use {
                    val responseBody = it.body?.string() ?: ""
                    rootFiles = Gson().fromJson<List<DatHostPathsItem>>(responseBody) ?: listOf()
                }
                val filteredFiles = rootFiles.filter { it.path.endsWith(".dem") }
                val scrimFolderResult = dropboxClient.files().listFolder(dropboxDemosFolder)
                val filesToUpload =
                    filteredFiles.filter { file -> !scrimFolderResult.entries.map { it.name }.contains(file.path) }
                val uploadedPaths = arrayListOf<String>()
                filesToUpload.forEach {
                    val getFileResponse = getFile(it.path)
                    val file = getFileResponse.body?.byteStream() ?: return@forEach
                    file.use { `in` ->
                        val uploadPath = "$dropboxDemosFolder/${it.path}"
                        dropboxClient.files().uploadBuilder(uploadPath)
                            .uploadAndFinish(`in`)
                        uploadedPaths.add(it.path)
                        getFileResponse.close()
                    }
                }
                if (uploadedPaths.isNotEmpty()) {
                    val stringBuilder = StringBuilder().appendln("New `.dem` replay files are available:")
                    uploadedPaths.forEach {
                        val shareLink = dropboxClient.sharing().createSharedLinkWithSettings("$dropboxDemosFolder/$it")
                        val title = "(de_)([a-z]*)".toRegex().find(it)?.value ?: "Unknown Map"
                        stringBuilder.appendln("$title - ${shareLink.url}")
                    }
                    val channel = jda.getTextChannelById(discordTextChannelId)
                    channel?.sendMessage(stringBuilder)?.queue()
                }
                delay(60000)
            }
        }
    }

    fun enableAutoClearQueue() {
        log.info("Started autoclear queue feature")
        val hour = autoclearHour.toInt()
        GlobalScope.launch {
            while (true) {
                val currentDate = Date()
                val clearTime = Calendar.getInstance()
                clearTime.timeZone = TimeZone.getDefault()
                clearTime.set(Calendar.HOUR_OF_DAY, hour)
                clearTime.set(Calendar.MINUTE, 0)
                clearTime.set(Calendar.SECOND, 0)
                if (clearTime.before(currentDate)) {
                    clearTime.add(Calendar.DATE, 1)
                    clearTime.set(Calendar.HOUR_OF_DAY, hour)
                    clearTime.set(Calendar.MINUTE, 0)
                    clearTime.set(Calendar.SECOND, 0)
                }
                val msBetween = clearTime.time.toInstant().toEpochMilli() - currentDate.toInstant().toEpochMilli()
                log.info("Autoclearing in ${msBetween}ms")
                delay(msBetween)
                val channel = jda.getTextChannelById(discordTextChannelId) ?: return@launch
                if (queue.size > 0) {
                    channel.sendMessage("Auto-clearing queue in 2 min").queue()
                    delay(2000)
                    queue.clear()
                    channel.sendMessage("Queue has been auto-cleared").queue()
                }
            }
        }
    }

    private inline fun <reified T> Gson.fromJson(json: String) = fromJson<T>(json, object : TypeToken<T>() {}.type)

}
