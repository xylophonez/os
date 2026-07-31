package org.permaweb.andee

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class AndeeService : Service() {
    private var cryptoAgent: AndeeCryptoAgent? = null
    private var hyperbeamRuntime: HyperbeamRuntime? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var starterThread: Thread? = null
    @Volatile private var stopping = false

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, notification(), foregroundType())
        acquireRuntimeWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TERMINATE -> {
                terminateProcess(startId)
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                stopping = true
                stopRuntime()
                stopForegroundCompat()
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
            ACTION_COPY_PUBLIC_URL -> {
                copyPublicUrlAsync()
                return START_STICKY
            }
        }
        if (stopping) {
            stopping = true
            stopRuntime()
            stopForegroundCompat()
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        stopping = false
        acquireRuntimeWakeLock()
        startRuntimeIfNeeded()
        return START_STICKY
    }

    private fun startRuntimeIfNeeded() {
        if (hyperbeamRuntime?.isAlive() == true || starterThread?.isAlive == true) return
        Thread {
            var agent: AndeeCryptoAgent? = null
            var runtime: HyperbeamRuntime? = null
            try {
                val runtimeRoot = RuntimeExtractor(this).extractIfNeeded()
                val currentAgent = AndeeCryptoAgent(this).also { it.start() }
                agent = currentAgent
                if (stopping) {
                    currentAgent.close()
                    return@Thread
                }
                cryptoAgent = currentAgent
                val policy = currentAgent.policyStatus()
                if (!policy.optBoolean("accepted", false)) {
                    Log.w(TAG, "policy rejected; starting HyperBEAM for rejected measurement")
                }
                runtime = HyperbeamRuntime(this, runtimeRoot).also { it.start() }
                if (stopping) {
                    runtime?.close()
                    agent?.close()
                    return@Thread
                }
                hyperbeamRuntime = runtime
                Log.i(TAG, "AndEE service running")
                // The node connects to its tunnel broker itself, via the
                // config's on/start tunnel@1.0 connect hook (peer + worker
                // count live there). Nothing to trigger from the app side.
            } catch (exc: Exception) {
                Log.e(TAG, "failed to start AndEE runtime", exc)
                runtime?.close()
                agent?.close()
                stopSelf()
            } finally {
                starterThread = null
            }
        }.also { thread ->
            starterThread = thread
            thread.start()
        }
    }

    override fun onDestroy() {
        stopRuntime()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.e(TAG, "foreground service timed out: startId=$startId type=$fgsType")
        stopping = true
        try {
            stopRuntime()
        } finally {
            stopForegroundCompat()
            stopSelfResult(startId)
        }
    }

    private fun terminateProcess(startId: Int) {
        stopping = true
        stopForegroundCompat()
        stopSelfResult(startId)
        Thread {
            try {
                stopRuntime()
                HyperbeamRuntime.killResidualRuntimeProcesses(this)
            } catch (exc: Exception) {
                Log.e(TAG, "hard terminate cleanup failed", exc)
            } finally {
                Runtime.getRuntime().halt(0)
            }
        }.start()
    }

    @Synchronized
    private fun stopRuntime() {
        hyperbeamRuntime?.close()
        hyperbeamRuntime = null
        cryptoAgent?.close()
        cryptoAgent = null
        starterThread = null
        releaseRuntimeWakeLock()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireRuntimeWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        wakeLock = powerManager
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:runtime")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
        Log.i(TAG, "runtime wake lock acquired")
    }

    private fun releaseRuntimeWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun copyPublicUrlAsync() {
        Thread {
            try {
                val address = runtimeAddress()
                require(address.length == 43) { "unexpected AndEE address" }
                val domain = tunnelDomain()
                    ?: throw IllegalStateException("no tunnel broker configured")
                val publicBaseUrl = "https://${base32Address(address)}.$domain/"
                require(publicAddress(publicBaseUrl) == address) { "public tunnel is not healthy" }
                val publicUrl = "${publicBaseUrl}~hyperbuddy@1.0/index"
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("AndEE public URL", publicUrl))
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification("Public URL copied"))
                Log.i(TAG, "public URL copied to clipboard")
            } catch (exc: Exception) {
                Log.e(TAG, "failed to copy public URL", exc)
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification("Public URL unavailable"))
            }
        }.start()
    }

    /** The public URL domain, derived from the configured tunnel broker peer. */
    private fun tunnelDomain(): String? {
        val peer = tunnelPeer() ?: return null
        return peer.substringAfter("://").substringBefore("/").ifEmpty { null }
    }

    private fun tunnelPeer(): String? =
        try {
            AndeeBootConfigStore.tunnelPeer(
                this,
                File(AndeePaths.runtimeRoot(this), "config/andee.json"),
            )
        } catch (exc: Exception) {
            Log.w(TAG, "could not read tunnel peer from config", exc)
            null
        }

    private fun publicAddress(publicUrl: String): String {
        val connection =
            URL("${publicUrl}~meta@1.0/info/address").openConnection() as HttpsURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.instanceFollowRedirects = false
        return try {
            require(connection.responseCode == 200) {
                "public address returned HTTP ${connection.responseCode}"
            }
            connection.inputStream.bufferedReader().use { it.readText().trim() }
        } finally {
            connection.disconnect()
        }
    }

    private fun runtimeAddress(): String {
        val stdout = File(noBackupFilesDir, "run/hyperbeam.stdout")
        val match = NODE_STARTED_PATTERN.findAll(stdout.readText()).lastOrNull()
            ?: error("AndEE runtime address not available")
        return match.groupValues[1]
    }

    private fun base32Address(address: String): String {
        val bytes = Base64.decode(address, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
        val output = StringBuilder((bytes.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                output.append(alphabet[(buffer shr bits) and 31])
            }
        }
        if (bits > 0) output.append(alphabet[(buffer shl (5 - bits)) and 31])
        return output.toString()
    }

    private fun notification(text: String = "Runtime active"): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val copyIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, AndeeService::class.java).setAction(ACTION_COPY_PUBLIC_URL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_andee)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .addAction(Notification.Action.Builder(0, "Copy public URL", copyIntent).build())
            .setOngoing(true)
            .build()
    }

    private fun foregroundType(): Int {
        return if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else if (Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
    }

    companion object {
        const val ACTION_STOP = "org.permaweb.andee.action.STOP"
        const val ACTION_TERMINATE = "org.permaweb.andee.action.TERMINATE"
        const val ACTION_COPY_PUBLIC_URL = "org.permaweb.andee.action.COPY_PUBLIC_URL"
        private const val TAG = "AndeeService"
        private const val CHANNEL_ID = "andee-runtime"
        private const val NOTIFICATION_ID = 7341
        private val NODE_STARTED_PATTERN =
            Regex("AndEE HyperBEAM node started on port \\d+ as ([A-Za-z0-9_-]{43})")
    }
}
