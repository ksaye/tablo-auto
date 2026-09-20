package io.github.ksaye.tabloauto

import org.json.JSONArray
import org.json.JSONObject

/**
 * One channel, with whatever is on it at the moment the list was fetched.
 *
 * Flattened from the server's `/api/now`, which nests the channel inside the airing. Everything
 * here is either shown in a browse list or used to build a URL; nothing else is kept.
 */
data class Channel(
    /** The server's own path, `/guide/channels/230`. */
    val path: String,
    val number: String,
    val callSign: String,
    val name: String,
    val isFast: Boolean,
    val nowTitle: String?,
    val nowSubtitle: String?
) {
    /** Trailing id from the path — what the audio URL is built from. */
    val id: String get() = path.substringAfterLast('/')

    /** "4.1 KDFW", the way a channel is spoken about. */
    val label: String get() = if (number.isBlank()) callSign else "$number $callSign"

    /**
     * The big line on a row and on the now-playing screen: what is actually on.
     *
     * The programme, not the channel — "4.1 KDFW" tells a driver nothing they can choose by, and
     * the channel is still right underneath. Falls back to the channel when the guide has nothing,
     * so a row is never blank.
     */
    val displayTitle: String
        get() = nowTitle ?: label

    /** The small line: the channel it is on, and the episode if there is one. */
    val displaySubtitle: String
        get() = if (nowTitle != null) listOfNotNull(label, nowSubtitle).joinToString(" · ")
        else name

    /** Kept for anywhere that wants the programme alone. */
    val description: String
        get() = listOfNotNull(nowTitle, nowSubtitle).joinToString(" · ").ifBlank { name }

    /** Everything a spoken search might plausibly match against. */
    fun matches(query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return false
        return number.lowercase().contains(q) ||
            callSign.lowercase().contains(q) ||
            name.lowercase().contains(q) ||
            nowTitle?.lowercase()?.contains(q) == true
    }

    companion object {
        /** Parse the `/api/now` array. Anything without a channel path is not playable, so it goes. */
        fun parseNow(body: String): List<Channel> {
            val out = mutableListOf<Channel>()
            val array = JSONArray(body)
            for (i in 0 until array.length()) {
                val entry = array.optJSONObject(i) ?: continue
                val channel = entry.optJSONObject("channel") ?: continue
                val path = channel.optString("path").takeIf { it.isNotBlank() } ?: continue
                val airing: JSONObject? = entry.optJSONObject("airing")
                out += Channel(
                    path = path,
                    number = channel.optString("number"),
                    callSign = channel.optString("callSign"),
                    name = channel.optString("name").ifBlank { channel.optString("callSign") },
                    isFast = channel.optBoolean("isFast"),
                    nowTitle = airing?.optString("title")?.takeIf { it.isNotBlank() },
                    nowSubtitle = airing?.optString("subtitle")?.takeIf { it.isNotBlank() && it != "null" }
                )
            }
            return out
        }

        /** The same shape, written back out for the on-disk cache. */
        fun toJson(channels: List<Channel>): String {
            val array = JSONArray()
            channels.forEach { c ->
                array.put(
                    JSONObject()
                        .put("path", c.path)
                        .put("number", c.number)
                        .put("callSign", c.callSign)
                        .put("name", c.name)
                        .put("isFast", c.isFast)
                        .put("nowTitle", c.nowTitle ?: JSONObject.NULL)
                        .put("nowSubtitle", c.nowSubtitle ?: JSONObject.NULL)
                )
            }
            return array.toString()
        }

        fun fromJson(body: String): List<Channel> {
            val array = JSONArray(body)
            return (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                Channel(
                    path = o.optString("path"),
                    number = o.optString("number"),
                    callSign = o.optString("callSign"),
                    name = o.optString("name"),
                    isFast = o.optBoolean("isFast"),
                    nowTitle = o.optString("nowTitle").takeIf { it.isNotBlank() && it != "null" },
                    nowSubtitle = o.optString("nowSubtitle").takeIf { it.isNotBlank() && it != "null" }
                )
            }.filter { it.path.isNotBlank() }
        }
    }
}
