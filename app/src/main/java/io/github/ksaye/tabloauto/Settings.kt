package io.github.ksaye.tabloauto

import android.content.Context

/** Where the server is and how to prove we may talk to it. */
object Settings {
    private const val FILE = "tablo-auto"
    private const val KEY_SERVER = "server"
    private const val KEY_COOKIE = "cookie"
    private const val KEY_LAST_CHANNEL = "last-channel"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Base address with no trailing slash, e.g. `https://tv.example.org`. */
    fun server(context: Context): String? =
        prefs(context).getString(KEY_SERVER, null)

    fun setServer(context: Context, value: String?) {
        prefs(context).edit().apply {
            if (value.isNullOrBlank()) remove(KEY_SERVER) else putString(KEY_SERVER, value)
        }.apply()
    }

    /** The whole `Cookie:` header value, or null when the server does not ask for one. */
    fun cookie(context: Context): String? =
        prefs(context).getString(KEY_COOKIE, null)?.let { SecretStore.decrypt(it) }

    fun setCookie(context: Context, value: String?) {
        val editor = prefs(context).edit()
        val sealed = value?.takeIf { it.isNotBlank() }?.let { SecretStore.encrypt(it) }
        if (sealed == null) editor.remove(KEY_COOKIE) else editor.putString(KEY_COOKIE, sealed)
        editor.apply()
    }

    /** The channel playing when the app was last used, so the car can pick it up again. */
    fun lastChannel(context: Context): String? =
        prefs(context).getString(KEY_LAST_CHANNEL, null)

    fun setLastChannel(context: Context, channelId: String?) {
        prefs(context).edit().apply {
            if (channelId == null) remove(KEY_LAST_CHANNEL) else putString(KEY_LAST_CHANNEL, channelId)
        }.apply()
    }

    /**
     * Turn what somebody typed into an address that can be fetched: assume https when no scheme
     * was given, and drop a trailing slash so paths can simply be appended.
     */
    fun normalize(input: String): String? {
        val trimmed = input.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        val withScheme =
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
            else "https://$trimmed"
        return withScheme
    }
}
