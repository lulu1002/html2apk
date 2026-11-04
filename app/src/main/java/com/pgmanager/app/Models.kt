package com.pgmanager.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ConnInfo(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 5432,
    val database: String = "postgres",
    val user: String,
    val password: String,
    val ssl: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("host", host).put("port", port)
        .put("database", database).put("user", user).put("password", password).put("ssl", ssl)

    companion object {
        fun fromJson(o: JSONObject) = ConnInfo(
            id = o.getString("id"),
            name = o.getString("name"),
            host = o.getString("host"),
            port = o.optInt("port", 5432),
            database = o.optString("database", "postgres"),
            user = o.getString("user"),
            password = o.optString("password", ""),
            ssl = o.optBoolean("ssl", false)
        )
    }
}

data class QueryResult(
    val columns: List<String>,
    val rows: List<List<String?>>,
    val message: String? = null,
    val millis: Long = 0,
    val truncated: Boolean = false
)

/** Guarda as conexões (inclusive senhas) criptografadas no aparelho. */
class ConnectionStore(context: Context) {
    private val prefs: SharedPreferences = try {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "pg_connections", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        context.getSharedPreferences("pg_connections_plain", Context.MODE_PRIVATE)
    }

    fun load(): List<ConnInfo> = try {
        val arr = JSONArray(prefs.getString("list", "[]") ?: "[]")
        (0 until arr.length()).map { ConnInfo.fromJson(arr.getJSONObject(it)) }
    } catch (e: Exception) {
        emptyList()
    }

    fun save(list: List<ConnInfo>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("list", arr.toString()).apply()
    }
}
