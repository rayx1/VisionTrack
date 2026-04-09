package com.example.personcounter

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.TextureView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Manages an IP camera RTSP stream via ExoPlayer (Media3).
 *
 * Usage:
 *   1. Call [connect] with the authenticated URL and a [TextureView] to render into.
 *   2. Call [getFrameBitmap] periodically on a background thread to get the current frame.
 *   3. Call [release] in onDestroy / when switching away from IP camera mode.
 */
class IpCameraManager(private val context: Context) {

    private var player: ExoPlayer? = null
    private var textureView: TextureView? = null

    /** True while a stream is connected and the player is in READY/BUFFERING state. */
    var isConnected: Boolean = false
        private set

    /** Optional callback for playback state changes (called on main thread). */
    var onStateChanged: ((connected: Boolean, errorMsg: String?) -> Unit)? = null

    // -------------------------------------------------------------------------
    // Connect
    // -------------------------------------------------------------------------

    /**
     * Starts playback of the given [streamUrl] and binds output to [view].
     * Must be called on the **main thread** (ExoPlayer requirement).
     *
     * @param streamUrl Full URL, credentials already injected if needed
     *                  (use [SettingsManager.buildAuthenticatedUrl]).
     * @param view      TextureView that will display the stream.
     */
    fun connect(streamUrl: String, view: TextureView) {
        release()   // clean up any previous player

        if (streamUrl.isBlank()) {
            onStateChanged?.invoke(false, "IP camera URL is empty")
            return
        }

        textureView = view

        player = ExoPlayer.Builder(context).build().also { exo ->
            exo.setVideoTextureView(view)
            exo.setMediaItem(MediaItem.fromUri(streamUrl))
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_READY -> {
                            isConnected = true
                            onStateChanged?.invoke(true, null)
                            Log.d(TAG, "Stream ready: $streamUrl")
                        }
                        Player.STATE_ENDED, Player.STATE_IDLE -> {
                            isConnected = false
                            onStateChanged?.invoke(false, null)
                        }
                        else -> { /* BUFFERING — do nothing */ }
                    }
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    isConnected = false
                    val msg = "Stream error: ${error.message}"
                    Log.e(TAG, msg, error)
                    onStateChanged?.invoke(false, msg)
                }
            })
            exo.prepare()
            exo.playWhenReady = true
        }
    }

    // -------------------------------------------------------------------------
    // Frame capture
    // -------------------------------------------------------------------------

    /**
     * Returns the current video frame as an ARGB_8888 [Bitmap], or null if
     * the player is not ready or the TextureView has no surface yet.
     *
     * Safe to call from any thread — TextureView.getBitmap() is thread-safe.
     */
    fun getFrameBitmap(): Bitmap? {
        val tv = textureView ?: return null
        return try {
            tv.getBitmap(tv.width.takeIf { it > 0 } ?: return null,
                         tv.height.takeIf { it > 0 } ?: return null)
        } catch (e: Exception) {
            Log.w(TAG, "getFrameBitmap failed: ${e.message}")
            null
        }
    }

    // -------------------------------------------------------------------------
    // Release
    // -------------------------------------------------------------------------

    /**
     * Releases ExoPlayer resources. Must be called on the **main thread**.
     */
    fun release() {
        player?.release()
        player = null
        textureView = null
        isConnected = false
    }

    companion object {
        private const val TAG = "IpCameraManager"
    }
}
