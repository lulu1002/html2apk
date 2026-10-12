package com.vpsmanager

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/** Guarda os servidores criptografados (AES256 via Android Keystore). */
class HostStore(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "hosts_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun load(): List<Host> {
        val arr = JSONArray(prefs.getString("hosts", "[]"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Host(
                id = o.getString("id"),
                name = o.getString("name"),
                host = o.getString("host"),
                port = o.optInt("port", 22),
                user = o.getString("user"),
                password = o.optString("password"),
                privateKey = o.optString("privateKey"),
                fingerprint = o.optString("fingerprint")
            )
        }
    }

    fun save(hosts: List<Host>) {
        val arr = JSONArray()
        hosts.forEach { h ->
            arr.put(JSONObject().apply {
                put("id", h.id); put("name", h.name); put("host", h.host)
                put("port", h.port); put("user", h.user)
                put("password", h.password); put("privateKey", h.privateKey)
                put("fingerprint", h.fingerprint)
            })
        }
        prefs.edit().putString("hosts", arr.toString()).apply()
    }
}
