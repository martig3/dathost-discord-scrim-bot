package com.martige

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.martige.model.DatHostGameServer
import net.dv8tion.jda.api.MessageBuilder
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.lang.IllegalStateException
import java.util.*
import java.util.concurrent.TimeUnit


class BotService {

    companion object {
        private val log: Logger = LoggerFactory.getLogger(BotService::class.java)
        private lateinit var serverIp: String
        lateinit var serverId: String
        var auth64String = "Basic "
        var dmTemplate = "Your scrim server is ready! Paste this into your console:"
        var roleId: Long = 0
        var channelId: Long = 0
        private var queue: ArrayList<User> = arrayListOf()
        fun init(props: Properties) {
            serverIp = props.getProperty("serverIp")
            serverId = props.getProperty("serverId")
            auth64String += Base64.getEncoder()
                .encodeToString("${props.getProperty("user")}:${props.getProperty("password")}".toByteArray())
            dmTemplate = props.getProperty("dmTemplate") ?: dmTemplate
            roleId = props.getProperty("roleId").toLong()
            channelId = props.getProperty("channelId").toLong()
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
            .append("The following users are queued:\n")
        queue.forEach { stringBuilder.append(" - <@${it.id}>\n") }
        event.channel.sendMessage(stringBuilder.toString()).queue()
    }

    fun startServer(event: MessageReceivedEvent, override: Boolean) {
        if (!event.guild.getMembersWithRoles(event.guild.getRoleById(roleId)).contains(event.member)) {
            event.channel.sendMessage("You do not have the correct role to use this command").queue()
            return
        }
        if (queue.size == 10 || override) {
            event.channel.sendMessage("Starting scrim server...").queue()
        } else {
            event.channel.sendMessage("Yo, <@${event.author.id}> I cant start the server because the queue isn't full")
                .queue()
            return
        }
        val randomPass = Math.random().toString().replace("0.", "")
        val httpClient = OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val json: MediaType? = "application/json; charset=utf-8".toMediaTypeOrNull()
        val formUrlEncoded: MediaType? = "application/x-www-form-urlencoded; charset=utf-8".toMediaTypeOrNull()
        val changePasswordRequest = Request.Builder()
            .url("https://dathost.net/api/0.1/game-servers/$serverId")
            .put("csgo_settings.password=$randomPass".toRequestBody(formUrlEncoded))
            .header("Authorization", auth64String)
            .build()
        val passwordChangeResponse = httpClient.newCall(changePasswordRequest).execute()
        log.info("Server password change response: ${passwordChangeResponse.message}")
        val stopServerRequest = Request.Builder()
            .url("https://dathost.net/api/0.1/game-servers/$serverId/stop")
            .post("".toRequestBody(json))
            .header("Authorization", auth64String)
            .build()
        val stopServerResponse = httpClient.newCall(stopServerRequest).execute()
        log.info("Server stop response: ${stopServerResponse.message}")
        val startServerRequest = Request.Builder()
            .url("https://dathost.net/api/0.1/game-servers/$serverId/start")
            .post("".toRequestBody(json))
            .header("Authorization", auth64String)
            .build()
        val startServerResponse = httpClient.newCall(startServerRequest).execute()
        log.info("Server start response: ${startServerResponse.message}")
        for (x in 0..24) {
            val serverStateRequest = Request.Builder()
                .url("https://dathost.net/api/0.1/game-servers")
                .get()
                .header("Authorization", auth64String)
                .build()
            val serverStateResponse = httpClient.newCall(serverStateRequest).execute()
            val responseBody = serverStateResponse.body?.string() ?: return
            val gameServers = Gson().fromJson<List<DatHostGameServer>>(responseBody)
            val gameServer = gameServers.first { it.id == serverId }
            if (gameServer.booting) {
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
        queue.forEach { user ->
            try {
            event.guild.moveVoiceMember(
                event.guild.getMember(user)!!,
                event.guild.getVoiceChannelById(channelId)
            ).queue()
            } catch (e: IllegalStateException) {
                log.info("${user.name} is not connected to the server")
            }
        }
        event.channel.sendMessage(
            MessageBuilder().append("Moved queued users to the scrim channel, your scrim is starting").setTTS(true)
                .build()
        ).queue()
        // cleanup
        queue.clear()
    }

    fun unknownCommand(event: MessageReceivedEvent) {
        event.channel.sendMessage("Unknown command, type `!help` for a list of valid commands").queue()
    }

    fun listCommands(event: MessageReceivedEvent) {
        val stringBuilder = StringBuilder().append("List of available commands:\n")
        Bot.Command.values()
            .filter { it != Bot.Command.UNKNOWN }
            .forEach { stringBuilder.append("`${it.command}` - ${it.description}\n") }
        event.channel.sendMessage(stringBuilder.toString()).queue()
    }

    private fun generateTemplate(password: String): String {
        return "$dmTemplate\n`connect $serverIp;password $password`"
    }

    inline fun <reified T> Gson.fromJson(json: String) = fromJson<T>(json, object : TypeToken<T>() {}.type)

}
