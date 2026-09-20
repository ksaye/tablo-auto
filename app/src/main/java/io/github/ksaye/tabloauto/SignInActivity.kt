package io.github.ksaye.tabloauto

import android.app.Activity
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.github.ksaye.tabloauto.databinding.ActivitySignInBinding

/**
 * Signs in to a tabloweb that is behind Microsoft Entra, once, on the phone.
 *
 * There is no native sign-in to have: the server authenticates browsers with an OpenID Connect
 * round trip and keeps the result in a cookie. So this *is* a browser — a WebView that goes
 * through the same sign-in a laptop would, after which the cookie it collected is kept and used
 * for every request the app makes, including the audio stream itself.
 *
 * That cookie is issued with a ten-year sliding expiry (so the television never asks a remote
 * control to type a password), which is what makes this a one-time exercise: the car never shows a
 * sign-in screen, because by then there is nothing left to sign in to.
 */
class SignInActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignInBinding
    private var server: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignInBinding.inflate(layoutInflater)
        setContentView(binding.root)

        server = Settings.server(this)
        if (server == null) {
            Toast.makeText(this, R.string.set_address_first, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(binding.web, true)
        }

        binding.web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
        }

        binding.web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                captureCookieIfPresent()
            }
        }

        // /signin sends the browser to Microsoft and back; landing anywhere on the site
        // afterwards means there is a session cookie to keep.
        binding.web.loadUrl("$server/signin")
    }

    private fun captureCookieIfPresent() {
        val address = server ?: return
        val manager = CookieManager.getInstance()
        manager.flush()
        val cookies = manager.getCookie(address) ?: return
        if (!cookies.contains(SESSION_COOKIE)) return

        Settings.setCookie(this, cookies)
        Toast.makeText(this, R.string.signed_in, Toast.LENGTH_SHORT).show()
        setResult(Activity.RESULT_OK)
        finish()
    }

    override fun onDestroy() {
        binding.web.destroy()
        super.onDestroy()
    }

    private companion object {
        /** The cookie tabloweb keeps its session in. */
        const val SESSION_COOKIE = "tablo.auth"
    }
}
