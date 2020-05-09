package com.martige

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.martige.model.DatHostGameServer
import net.dv8tion.jda.api.MessageBuilder
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


class BotService {

    companion object {
        private val log: Logger = LoggerFactory.getLogger(BotService::class.java)
        private lateinit var gameServerIp: String
        lateinit var gameServerId: String
        var auth64String = "Basic "
        var dmTemplate = "Your scrim server is ready! Paste this into your console:"
        var discordPrivilegeRoleId: Long = 0
        var discordVoiceChannelId: Long = 0
        private var queue: ArrayList<User> = arrayListOf()
        val httpClient = OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        fun init(props: Properties) {
            gameServerIp = props.getProperty("gameserver.ip")
            gameServerId = props.getProperty("gameserver.id")
            auth64String += Base64.getEncoder()
                .encodeToString(
                    "${props.getProperty("dathost.username")}:${props.getProperty("dathost.password")}"
                        .toByteArray()
                )
            dmTemplate = props.getProperty("dm.template") ?: dmTemplate
            discordPrivilegeRoleId = props.getProperty("discord.role.privilege.id").toLong()
            discordVoiceChannelId = props.getProperty("discord.voicechannel.id").toLong()
        }
    }

    fun addToQueue(event: MessageReceivedEvent) {
        if (queue.size == 10) {
            event.channel.sendMessage("10 players are ready! Type !start to launch the scrim server").queue()
        } else {
            if (!queue.contains(event.author)) {
                queue.add(event.author)
                event.channel.sendMessage("<@${event.author.id}> has joined the queue!").queue()
                event.channel.sendMessage("Queue size: ${queue.size}/10").queue()
            } else {
                event.channel.sendMessage("<@${event.author.id}> is already in the queue").queue()
            }
        }
    }

    fun removeFromQueue(event: MessageReceivedEvent) {
        if (queue.contains(event.author)) {
            queue.remove(event.author)
            event.channel.sendMessage("<@${event.author.id}> has left the queue, see ya!").queue()
            event.channel.sendMessage("Queue size: ${queue.size}/10").queue()
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
        val stringBuilder = StringBuilder()
            .append("The following users are queued: (${queue.size}/10)\n")
        queue.forEach { stringBuilder.append("- @${it.name}\n") }
        event.channel.sendMessage(stringBuilder.toString()).queue()
    }

    fun clearQueue(event: MessageReceivedEvent) {
        if (!isMemberPrivileged(event)) {
            event.channel.sendMessage("You do not have the correct role for this command").queue()
        } else {
            queue.clear()
            event.channel.sendMessage("Queue cleared").queue()
        }
    }

    fun startServer(event: MessageReceivedEvent, force: Boolean) {
        if (!isMemberPrivileged(event) && force) return
        val gameServer = firstGameServer(httpClient) ?: return
        val isEmpty = gameServer.players_online == 0
        if ((queue.size == 10 || force) && isEmpty) {
            event.channel.sendMessage("Starting scrim server...").queue()
        } else {
            if (!isEmpty) event.channel
                .sendMessage("Yo, <@${event.author.id}> I cant start the server it's not empty")
                .queue()
            else
                event.channel
                    .sendMessage("Yo, <@${event.author.id}> I cant start the server because the queue isn't full")
                    .queue()
            return
        }
        val randomPass = Math.random().toString().replace("0.", "")
        val passwordChangeResponse = changeGameServerPassword(randomPass)
        log.info("Server password change response: ${passwordChangeResponse.message}")
        val stopServerResponse = stopGameServer()
        log.info("Server stop response: ${stopServerResponse.message}")
        val startServerResponse = startGameServer()
        log.info("Server start response: ${startServerResponse.message}")
        for (x in 0..24) {
            val gameServerPing = firstGameServer(httpClient) ?: return
            if (gameServerPing.booting) {
                log.info("Server is still booting, waiting 5s...")
                Thread.sleep(5000)
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
        event.channel.sendMessage(
            MessageBuilder().append("Moved queued users to the scrim channel, your scrim is starting")
                .setTTS(true)
                .build()
        ).queue()
        if (unconnectedUsers.size > 0) {
            val stringBuilder =
                StringBuilder().append("The following queued users are not in the discord and cannot be moved to the default scrim voice channel:\n")
            unconnectedUsers.forEach { stringBuilder.append("- <@${it.id}>") }
            event.channel.sendMessage(stringBuilder.toString()).queue()
        }
        // cleanup
        queue.clear()
        log.info("Startup process has completed successfully")
    }

    fun unknownCommand(event: MessageReceivedEvent) {
        if (!event.message.contentRaw.startsWith("!")) return
        event.channel.sendMessage("Unknown command, type `!help` for a list of valid commands").queue()
    }

    fun listCommands(event: MessageReceivedEvent) {
        val stringBuilder = StringBuilder().append("List of available commands:\n")
        Bot.Command.values()
            .filter { it != Bot.Command.UNKNOWN }
            .forEach { stringBuilder.append("`${it.command}` - ${it.description}\n") }
        event.channel.sendMessage(stringBuilder.toString()).queue()
    }

    private fun firstGameServer(httpClient: OkHttpClient): DatHostGameServer? {
        val serverStateRequest = Request.Builder()
            .url("https://dathost.net/api/0.1/game-servers")
            .get()
            .header("Authorization", auth64String)
            .build()
        val serverStateResponse = httpClient.newCall(serverStateRequest).execute()
        val responseBody = serverStateResponse.body?.string() ?: return null
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

    private fun generateTemplate(password: String): String {
        return "$dmTemplate\n`connect $gameServerIp;password $password`"
    }

    private fun isMemberPrivileged(event: MessageReceivedEvent): Boolean {
        return event.guild.getMembersWithRoles(event.guild.getRoleById(discordPrivilegeRoleId))
                .contains(event.member)
    }

    inline fun <reified T> Gson.fromJson(json: String) = fromJson<T>(json, object : TypeToken<T>() {}.type)

}
