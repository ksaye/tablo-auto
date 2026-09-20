package io.github.ksaye.tabloauto

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What a connection test found. */
sealed class Probe {
    data class Ok(val channels: Int, val signedInAs: String?) : Probe()

    /** The server wants an Entra sign-in and this app has not done one, or the cookie expired. */
    object NeedsSignIn : Probe()

    data class Failed(val reason: String) : Probe()
}

/**
 * Talks to a tabloweb server.
 *
 * The same OkHttp client is handed to the player, so the stream is fetched by exactly the thing
 * that knows about the sign-in cookie — one place where the credential is attached rather than
 * two that can disagree.
 */
class TabloClient(private val context: Context) {

    val http: OkHttpClient = OkHttpClient.Builder()
        // Generous but finite. A phone on a weak cell needs longer than a desktop does, and a
        // request that is never going to finish should fail so the reconnect logic can have a go.
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(CookieInterceptor())
        .build()

    /**
     * Attach the sign-in cookie, but only to our own server: this header is a credential and it
     * has no business following a redirect off to somewhere else.
     */
    private inner class CookieInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val cookie = Settings.cookie(context)
            val serverHost = Settings.server(context)?.toHttpUrlOrNull()?.host
            val sameHost = serverHost != null && request.url.host.equals(serverHost, ignoreCase = true)
            val forwarded =
                if (cookie.isNullOrBlank() || !sameHost) request
                else request.newBuilder().header("Cookie", cookie).build()
            return chain.proceed(forwarded)
        }
    }

    private fun server(): String =
        Settings.server(context) ?: throw IOException("No Tablo address has been set yet.")

    /** The address of one channel's sound. Stable — see the note on reconnecting in Reconnector. */
    fun audioUrl(channel: Channel): String =
        "${server()}/api/audio/channel/${channel.id}.m3u8"

    /** Every channel, with what is on now. */
    suspend fun channels(): List<Channel> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("${server()}/api/now").build()
        http.newCall(request).execute().use { response ->
            if (response.code == 401) throw NeedsSignInException()
            if (!response.isSuccessful) throw IOException("The server answered ${response.code}.")
            Channel.parseNow(response.body?.string().orEmpty())
        }
    }

    /** Is this address reachable, and does it know who we are? */
    suspend fun probe(): Probe = withContext(Dispatchers.IO) {
        try {
            val channels = channels()
            Probe.Ok(channels.size, signedInAs())
        } catch (e: NeedsSignInException) {
            Probe.NeedsSignIn
        } catch (e: Exception) {
            Probe.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** The name the server has for whoever is signed in, when it is doing sign-ins at all. */
    private fun signedInAs(): String? = try {
        val request = Request.Builder().url("${server()}/api/me").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null
            else JSONObject(response.body?.string().orEmpty())
                .optString("name").takeIf { it.isNotBlank() && it != "null" }
        }
    } catch (e: Exception) {
        null
    }
}

/** The server is behind a sign-in and this app has not got through it. */
class NeedsSignInException : IOException("Sign in to the Tablo web site first.")
