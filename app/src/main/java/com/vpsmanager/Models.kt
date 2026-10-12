package com.vpsmanager

import java.util.UUID

data class Host(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 22,
    val user: String,
    val password: String = "",
    val privateKey: String = ""
)
