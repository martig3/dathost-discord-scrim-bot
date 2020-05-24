package com.martige

import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.MemberCachePolicy
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.FileInputStream
import java.util.*

class Bot : ListenerAdapter() {

    override fun onMessageReceived(event: MessageReceivedEvent) {
        if (event.author.isBot) return
        if (event.channel.id != discordTextChannelId.toString()) return
        val regex = "^([\\w!]+)".toRegex()
        when (Command.valueOfLabel(regex.find(event.message.contentStripped.toLowerCase().trim())?.value)
            ?: Command.UNKNOWN) {
            Command.JOIN -> botService.addToQueue(event)
            Command.LEAVE -> botService.removeFromQueue(event)
            Command.LIST -> botService.listQueue(event)
            Command.START -> botService.startServer(event)
            Command.RECOVER -> botService.recoverQueue(event)
            Command.CLEARQUEUE -> botService.clearQueue(event)
            Command.HELP -> botService.listCommands(event)
            Command.UNKNOWN -> botService.unknownCommand(event)
        }
    }


    companion object {
        private val log: Logger = LoggerFactory.getLogger(Bot::class.java)
        private var props = loadProps()
        var discordTextChannelId: Long = props.getProperty("discord.textchannel.id").toLong()
        private val enableDemUpload = props.getProperty("dropbox.upload") ?: "true"
        lateinit var botService: BotService

        @JvmStatic
        fun main(args: Array<String>) {
            val botToken = props.getProperty("bot.token")
            val jda = JDABuilder
                .create(
                    botToken,
                    GatewayIntent.GUILD_MESSAGES,
                    GatewayIntent.GUILD_PRESENCES,
                    GatewayIntent.GUILD_VOICE_STATES,
                    GatewayIntent.GUILD_EMOJIS,
                    GatewayIntent.GUILD_MEMBERS,
                    GatewayIntent.DIRECT_MESSAGES
                )
                .setMemberCachePolicy(MemberCachePolicy.ONLINE)
                .addEventListeners(Bot())
                .build()
            botService = BotService(props, jda)
            if (enableDemUpload.toBoolean()) botService.enableDemoUpload()
            log.info("JDA Build Successful, BOT Running")
        }

        private fun loadProps(): Properties {
            val props = Properties()
            props.load(FileInputStream("bot.properties"))
            return props
        }
    }


    enum class Command(val command: String, val description: String) {
        JOIN("!join", "Join the scrim queue"),
        LEAVE("!leave", "Leave the scrim queue"),
        LIST("!list", "Lists all users in scrim queue"),
        START("!start", "Start the scrim after the queue is full. Add \"-force\" to force start (privileged argument)"),
        RECOVER("!recover", "Tag all users after command to create new queue (privileged)"),
        CLEARQUEUE("!clearqueue", "Clears the queue (privileged)"),
        HELP("!help", "What you are currently seeing"),
        UNKNOWN("", "Placeholder for unknown commands");

        companion object {
            fun valueOfLabel(label: String?): Command? {
                for (e in values()) {
                    if (e.command == label) {
                        return e
                    }
                }
                return null
            }
        }
    }
}
