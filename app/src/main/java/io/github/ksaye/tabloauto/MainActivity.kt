package io.github.ksaye.tabloauto

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import io.github.ksaye.tabloauto.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.io.File

/**
 * The phone screen: where the address is typed, the sign-in happens once, and playback can be
 * tried without getting in a car.
 *
 * Everything the driver ever sees is in [TabloMediaService]; this exists so that by the time the
 * phone is on a dock there is nothing left to configure.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var client: TabloClient
    private var controller: MediaController? = null
    private var pendingUpdate: Update? = null

    private val signIn = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        testConnection()
    }

    private val notifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        client = TabloClient(this)

        binding.serverUrl.setText(Settings.server(this).orEmpty())
        binding.versionLine.text = getString(R.string.version_line, BuildConfig.VERSION_NAME)

        binding.saveAndTest.setOnClickListener {
            val normalized = Settings.normalize(binding.serverUrl.text.toString())
            if (normalized == null) {
                binding.status.text = getString(R.string.enter_an_address)
                return@setOnClickListener
            }
            Settings.setServer(this, normalized)
            binding.serverUrl.setText(normalized)
            testConnection()
        }

        binding.signIn.setOnClickListener {
            signIn.launch(android.content.Intent(this, SignInActivity::class.java))
        }

        binding.playTest.setOnClickListener { playFirstChannel() }
        binding.stopTest.setOnClickListener { controller?.stop() }
        binding.installUpdate.setOnClickListener { installUpdate() }

        // The media notification is how playback is controlled away from the car.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (Settings.server(this) != null) testConnection()
        checkForUpdate()
    }

    override fun onStart() {
        super.onStart()
        connectController()
    }

    override fun onStop() {
        controller?.release()
        controller = null
        super.onStop()
    }

    // ------------------------------------------------------------------ connection

    private fun testConnection() {
        binding.status.text = getString(R.string.checking)
        binding.signIn.visibility = View.GONE
        lifecycleScope.launch {
            when (val probe = client.probe()) {
                is Probe.Ok -> {
                    binding.status.text = if (probe.signedInAs != null) {
                        getString(R.string.connected_as, probe.channels, probe.signedInAs)
                    } else {
                        getString(R.string.connected, probe.channels)
                    }
                }
                Probe.NeedsSignIn -> {
                    binding.status.text = getString(R.string.needs_sign_in)
                    binding.signIn.visibility = View.VISIBLE
                }
                is Probe.Failed -> {
                    binding.status.text = getString(R.string.could_not_connect, probe.reason)
                    binding.signIn.visibility = View.VISIBLE
                }
            }
        }
    }

    // ------------------------------------------------------------------ trying it out

    private fun connectController() {
        val token = SessionToken(this, ComponentName(this, TabloMediaService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({
            controller = try {
                future.get()
            } catch (e: Exception) {
                null
            }
            controller?.addListener(object : androidx.media3.common.Player.Listener {
                override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
                    binding.nowPlaying.text = listOfNotNull(
                        mediaMetadata.title?.toString(),
                        mediaMetadata.subtitle?.toString()
                    ).joinToString(" — ")
                }
            })
        }, MoreExecutors.directExecutor())
    }

    /**
     * Play the first channel, exactly the way a tap in the car does: hand the service a media id
     * and let it work out the rest.
     */
    private fun playFirstChannel() {
        lifecycleScope.launch {
            val channels = try {
                client.channels()
            } catch (e: Exception) {
                emptyList()
            }
            val first = channels.firstOrNull { !it.isFast } ?: channels.firstOrNull()
            if (first == null) {
                Toast.makeText(this@MainActivity, R.string.no_channels, Toast.LENGTH_LONG).show()
                return@launch
            }
            val player = controller
            if (player == null) {
                Toast.makeText(this@MainActivity, R.string.player_not_ready, Toast.LENGTH_SHORT).show()
                return@launch
            }
            player.setMediaItem(MediaItem.Builder().setMediaId("channel/${first.id}").build())
            player.prepare()
            player.play()
        }
    }

    // ------------------------------------------------------------------ updates

    private fun checkForUpdate() {
        if (!Updater.configured) {
            binding.updateStatus.text = getString(R.string.updates_off)
            return
        }
        binding.updateStatus.text = getString(R.string.checking_for_updates)
        lifecycleScope.launch {
            val update = Updater.check(this@MainActivity, client.http)
            pendingUpdate = update
            if (update == null) {
                binding.updateStatus.text = getString(R.string.up_to_date, BuildConfig.VERSION_NAME)
                binding.installUpdate.visibility = View.GONE
            } else {
                binding.updateStatus.text = getString(R.string.update_available, update.version)
                binding.installUpdate.visibility = View.VISIBLE
            }
        }
    }

    private fun installUpdate() {
        val update = pendingUpdate ?: return
        binding.installUpdate.isEnabled = false
        binding.updateStatus.text = getString(R.string.downloading)
        lifecycleScope.launch {
            val apk: File? = Updater.download(this@MainActivity, client.http, update)
            binding.installUpdate.isEnabled = true
            if (apk == null) {
                binding.updateStatus.text = getString(R.string.download_failed)
                return@launch
            }
            if (!Updater.install(this@MainActivity, apk)) {
                binding.updateStatus.text = getString(R.string.allow_installs)
            }
        }
    }
}
