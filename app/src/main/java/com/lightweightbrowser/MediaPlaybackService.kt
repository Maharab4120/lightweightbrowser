package com.lightweightbrowser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.webkit.WebView
import java.lang.ref.WeakReference

/**
 * Foreground media service with real notification controls. The service itself
 * does not decode audio: the WebView does. Controls are forwarded to the active
 * page with a tiny JavaScript command, so Play/Pause/Stop work on any HTML5
 * media the browser can play.
 */
class MediaPlaybackService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaSession: MediaSession? = null
    private var playing = true

    private val sessionCallback = object : MediaSession.Callback() {
        override fun onPlay() = setPlaying(true)
        override fun onPause() = setPlaying(false)
        override fun onStop() {
            setPlaying(false)
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        mediaSession = MediaSession(this, SESSION_TAG).apply {
            setCallback(sessionCallback)
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            isActive = true
        }
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "LightweightBrowser:media"
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                userPaused = true
                releaseResources()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PLAY, ACTION_RESUME -> setPlaying(true)
            ACTION_PAUSE -> setPlaying(false)
        }
        ensureWakeLock()
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    private fun setPlaying(value: Boolean) {
        playing = value
        userPaused = !value
        if (value) ensureWakeLock() else releaseWakeLock()
        runOnPage(AdBlocker.mediaControlJs(if (value) "play" else "pause"))
        updatePlaybackState()
        refreshNotification()
    }

    private fun ensureWakeLock() {
        val lock = wakeLock ?: return
        if (!lock.isHeld) lock.acquire()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    private fun updatePlaybackState() {
        val state = if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        val actions = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_STOP
        mediaSession?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(state, 0L, 0f)
                .build()
        )
    }

    private fun refreshNotification() {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            // Notification may not be posted yet.
        }
    }

    private fun releaseResources() {
        releaseWakeLock()
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
    }

    override fun onDestroy() {
        releaseResources()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Media playback",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Controls for browser audio playing in the background"
            manager.createNotificationChannel(channel)
        }
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val openApp = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(this, 0, openApp, pendingFlags())

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        val toggleIcon = if (playing) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        val toggleLabel = if (playing) "Pause" else "Play"
        val toggleAction = if (playing) ACTION_PAUSE else ACTION_PLAY

        val toggle = Notification.Action.Builder(
            toggleIcon, toggleLabel, servicePendingIntent(toggleAction)
        ).build()
        val stop = Notification.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Stop",
            servicePendingIntent(ACTION_STOP)
        ).build()

        return builder
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Lightweight Browser")
            .setContentText(if (playing) "Playing audio in the background" else "Playback paused")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(toggle)
            .addAction(stop)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
            .build()
    }

    @Suppress("DEPRECATION")
    private fun servicePendingIntent(action: String): PendingIntent {
        val intent = Intent(this, MediaPlaybackService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(this, action.hashCode(), intent, pendingFlags())
    }

    private fun pendingFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE
            } else {
                0
            }

    companion object {
        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"
        private const val ACTION_PLAY = "play"
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_RESUME = "resume"
        private const val CHANNEL_ID = "media_playback"
        private const val NOTIFICATION_ID = 1001
        private const val SESSION_TAG = "LightweightBrowserMedia"

        /** Set by the notification so the activity will not auto-resume. */
        @Volatile
        var userPaused = false

        private val mainHandler = Handler(Looper.getMainLooper())

        @Volatile
        private var targetWebView: WeakReference<WebView>? = null

        fun registerWebView(view: WebView?) {
            targetWebView = view?.let { WeakReference(it) }
        }

        /** Run a small command on the page that is currently producing audio. */
        fun runOnPage(js: String) {
            mainHandler.post {
                targetWebView?.get()?.evaluateJavascript(js, null)
            }
        }

        fun start(context: Context) = sendAction(context, ACTION_START)

        fun resume(context: Context) = sendAction(context, ACTION_RESUME)

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, MediaPlaybackService::class.java))
            } catch (e: Exception) {
                // Service may already be stopped.
            }
        }

        private fun sendAction(context: Context, action: String) {
            val intent = Intent(context, MediaPlaybackService::class.java).apply {
                this.action = action
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // Background start restrictions can block this on some OS versions.
            }
        }
    }
}
