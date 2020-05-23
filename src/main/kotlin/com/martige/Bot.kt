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
            Command.JOIN -> BotService(props).addToQueue(event)
            Command.LEAVE -> BotService(props).removeFromQueue(event)
            Command.LIST -> BotService(props).listQueue(event)
            Command.START -> BotService(props).startServer(event, false)
            Command.STARTOVERRIDE -> BotService(props).startServer(event, true)
            Command.RECOVER -> BotService(props).recoverQueue(event)
            Command.CLEARQUEUE -> BotService(props).clearQueue(event)
            Command.HELP -> BotService(props).listCommands(event)
            Command.UNKNOWN -> BotService(props).unknownCommand(event)
        }
    }


    companion object {
        private val log: Logger = LoggerFactory.getLogger(Bot::class.java)
        private var props = getProps()
        var discordTextChannelId: Long = props.getProperty("discord.textchannel.id").toLong()

        @JvmStatic
        fun main(args: Array<String>) {
            val botToken = props.getProperty("bot.token")
            BotService(props)
            JDABuilder
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
        STARTOVERRIDE("!start -force", "Start the scrim even if the queue is not full (privileged)"),
        RECOVER( "!recover", "Tag all users after command to create new queue (privileged)"),
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
