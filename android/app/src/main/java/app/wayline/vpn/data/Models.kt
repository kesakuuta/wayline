package app.wayline.vpn.data

import org.json.JSONArray
import org.json.JSONObject

data class Server(
    val id: String,
    val name: String,
    val protocol: String,
    val address: String,
    val port: Int,
    val raw: String,
    val transport: String = "tcp",
    val flag: String = "UN",
    val favorite: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("protocol", protocol)
        put("address", address)
        put("port", port)
        put("raw", raw)
        put("transport", transport)
        put("flag", flag)
        put("favorite", favorite)
    }

    companion object {
        fun fromJson(json: JSONObject): Server {
            return Server(
                id = json.optString("id", java.util.UUID.randomUUID().toString()),
                name = json.optString("name", "Server"),
                protocol = json.optString("protocol", "vless"),
                address = json.optString("address", "127.0.0.1"),
                port = json.optInt("port", 443),
                raw = json.optString("raw", ""),
                transport = json.optString("transport", "tcp"),
                flag = json.optString("flag", "UN"),
                favorite = json.optBoolean("favorite", false)
            )
        }
    }
}

data class Subscription(
    val id: String,
    val name: String,
    val updated: String,
    val servers: List<Server> = emptyList(),
    val sourceUrl: String? = null,
    val expireAt: Long? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("updated", updated)
        put("sourceUrl", sourceUrl ?: JSONObject.NULL)
        if (expireAt != null) put("expireAt", expireAt)
        val arr = JSONArray()
        servers.forEach { arr.put(it.toJson()) }
        put("servers", arr)
    }

    companion object {
        fun fromJson(json: JSONObject): Subscription {
            val serversArr = json.optJSONArray("servers") ?: JSONArray()
            val list = mutableListOf<Server>()
            for (i in 0 until serversArr.length()) {
                val item = serversArr.optJSONObject(i)
                if (item != null) list.add(Server.fromJson(item))
            }
            return Subscription(
                id = json.optString("id", java.util.UUID.randomUUID().toString()),
                name = json.optString("name", "Подписка"),
                updated = json.optString("updated", "Только что"),
                servers = list,
                sourceUrl = if (json.isNull("sourceUrl")) null else json.optString("sourceUrl"),
                expireAt = if (json.has("expireAt")) json.optLong("expireAt") else null
            )
        }
    }
}
