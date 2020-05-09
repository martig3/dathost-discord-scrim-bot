# dathost-discord-scrim-bot
Simple Discord bot for managing a queue for 10 man scrims in CSGO using DatHost's API

## Use Case
A way to organize 10 man scrims on a DatHost dedicated CSGO server via Discord.

## Usage
The bot is bound to one discord server and utilizes a specific text channel to recieve commands. Users type `!join` to enter the queue. Once the queue is full, a user with a specified server role (preferably an admin role) types `!start`

#### Available Commands
- `!join` - Join the scrim queue
- `!leave` - Leave the scrim queue
- `!list` - Lists all users in scrim queue
- `!start` - Start the scrim after the queue is full
- `!start -override` - Start the scrim even if the queue is not full
- `!help` - generates help message

## `!start` Sequence
- Changes server password to random integer
- Stops the server (if started & no player count is 0)
- Starts the server
- Waits until server is booted
- DMs users with connection info
- Pulls users into a specified voice channel with a TTS alert message
- If user in queue is not connected to server, it DMs them to join & notifies bound text channel that they could not be moved
- Clears queue

## Setup
This repo currently does not have any CI/CD, to run please clone & create a `bot.properties` file in the root directory & use `gradle run`. See an example of the `bot.properties` below.
### Properties
- `gameserver.ip` - game server IP address
- `gameserver.id` - game server id (see https://dathost.net/api if you cant find this)
- `dathost.username` - your DatHost username 
- `dathost.password` - your DatHost password
- `dm.template` - a template for what the DM message header says
- `discord.role.privilege.id` - a role id for users that can start the server
- `discord.voicechannel.id` - a default voice channel to pull users in once the server is started up
- `discord.textchannel.id` - the channel that listens for commands
- `bot.token` - the bot token
### `bot.properties` Example
```gameserver.ip=example-domain.datho.st:28453
gameserver.id=5e862a7cb89211b154e8a4882141
dathost.username=exampleusername@example.com 
dathost.password=examplepassword
dm.template=Your scrim server is ready! Paste this into your console:
discord.role.privilege.id=269231231231947290082
discord.voicechannel.id=442712312414804316
discord.textchannel.id=696363464140812447
bot.token=NM354711923Nj123TM1.SFSxA.RzAgGHS41231kONw1qYSe6FQUHJCsM