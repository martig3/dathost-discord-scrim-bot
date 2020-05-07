package com.martige.model

data class CsgoSettings(
    val autoload_configs: List<Any>?,
    val disable_bots: Boolean?,
    val enable_csay_plugin: Boolean?,
    val enable_gotv: Boolean?,
    val enable_sourcemod: Boolean?,
    val game_mode: String?,
    val insecure: Boolean?,
    val mapgroup: String?,
    val mapgroup_start_map: String?,
    val maps_source: String?,
    val password: String?,
    val pure_server: Boolean?,
    val rcon: String?,
    val slots: Int?,
    val sourcemod_admins: String?,
    val sourcemod_plugins: List<String>?,
    val steam_game_server_login_token: String?,
    val tickrate: Double?,
    val workshop_authkey: String?,
    val workshop_id: String?,
    val workshop_start_map_id: String?
)
