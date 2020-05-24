# dathost-discord-scrim-bot
Simple Discord bot for managing a queue for 10 man scrims in CSGO using DatHost's API

## Use Case
A way to organize 10 man scrims on a DatHost dedicated CSGO server via Discord.

## Usage
The bot must be bound to one discord server and utilizes a specific text channel to receive commands. Users type `!join` to enter the queue. Once the queue is full, the `!start` command can be executed.

#### Available Commands
- `!join` - Join the scrim queue
- `!leave` - Leave the scrim queue
- `!list` - Lists all users in scrim queue
- `!clearqueue` - Clears the active queue (Privileged)
- `!start` - Start the scrim after the queue is full. Add `-force` force start (privileged argument)
- `!recover` - Tag all users after command to create a new queue (Privileged)
- `!help` - generates help message

## `!start` Sequence
- Changes server password to random integer
- Stops the server (if started & no player count is 0)
- Starts the server
- Waits until the server is booted
- DMs users with connection info
- Pulls users into a specified voice channel with a TTS alert message
- If a user in the queue is not connected to server, it DMs them to join & notifies bound text channel that they could not be moved
- Clears queue

## Dropbox Integration
This bot will automatically upload `.dem` replay files stored on your game server if the `dropbox.token` property has been set to `true`. It is recommended to set up your dropbox app to only use one folder for the sake of simplicity.

## Setup
This repo currently does not have any CI/CD, to run please clone & create a `bot.properties` file in the root directory & use `gradle run`. See an example of the `bot.properties` below.

_Note: Discord ids can be accessed by enabling developer mode_
### Properties
- `gameserver.ip` - game server IP address
- `gameserver.id` - game server id ([use this to find server id](https://dathost.net/api#!/default/get_game_servers))
- `dathost.username` - your DatHost username 
- `dathost.password` - your DatHost password
- `dm.template` - _(Optional)_ a template for what the DM message header says
- `discord.role.privilege.id` - a role id for users that can start the server
- `discord.voicechannel.id` - a default voice channel to pull users in once the server has started up
- `discord.textchannel.id` - the channel that listens for commands
- `bot.token` - the bot token
- `bot.autoclear` - _(Optional)_ enables auto-clearing of queue. Defaults to `false`
- `bot.autoclear.hourofday` - _(Optional)_ specify the hour of day to autoclear queue (in 24h format). Defaults to `7`
- `dropbox.upload` - _(Optional)_ enables the dropbox `.dem` replay files integration. Defaults to `false`
- `dropbox.token` - _(Optional)_ your dropbox api token
- `dropbox.sharedfolder` - _(Optional)_ use only if your dropbox app has been configured to access all of your directories, although that is recommended for this use case
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
bot.autoclear=true
bot.autoclear.hourofday=7
dropbox.upload=true
dropbox.token=DlQlJm5kf-gAAAAAAAAmwzNYgNDpIQsgCO7-OoJfFTZFlMTMih7hoiIB7dMXs9Ea
