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
        if (event.channel.id != channelId.toString()) return
        when (Command.valueOfLabel(event.message.contentStripped.toLowerCase().trim()) ?: Command.UNKNOWN) {
            Command.JOIN -> BotService().addToQueue(event)
            Command.LEAVE -> BotService().removeFromQueue(event)
            Command.LIST -> BotService().listQueue(event)
            Command.START -> BotService().startServer(event, false)
            Command.STARTOVERRIDE -> BotService().startServer(event, true)
            Command.HELP -> BotService().listCommands(event)
            Command.UNKNOWN -> BotService().unknownCommand(event)
        }
    }


    companion object {
        private val log: Logger = LoggerFactory.getLogger(Bot::class.java)
        var channelId: Long = 0

        @JvmStatic
        fun main(args: Array<String>) {
            val props = getProps()
            channelId = props.getProperty("channelId").toLong()
            val token = props.getProperty("token")
            BotService.init(props)
            JDABuilder
                .create(
                    token,
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
            log.info("JDA Build Successful, BOT Running")
        }

        private fun getProps(): Properties {
            val props = Properties()
            props.load(FileInputStream("bot.properties"))
            return props
        }
    }


    enum class Command(val command: String, val description: String) {
        JOIN("!join", "Join the scrim queue"),
        LEAVE("!leave", "Leave the scrim queue"),
        LIST("!list", "Lists all users in scrim queue"),
        START("!start", "Start the scrim after the queue is full"),
        STARTOVERRIDE("!start -override", "Start the scrim even if the queue is not full"),
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
