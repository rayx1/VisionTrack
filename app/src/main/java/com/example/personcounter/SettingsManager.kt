package com.example.personcounter

import android.content.Context
import android.content.SharedPreferences

/**
 * Thin SharedPreferences wrapper.
 * All reads have sensible defaults; writes are applied immediately (commit()).
 */
class SettingsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // -------------------------------------------------------------------------
    // Detection threshold
    // -------------------------------------------------------------------------

    fun getThreshold(): Float = prefs.getFloat(KEY_THRESHOLD, DEFAULT_THRESHOLD)

    fun setThreshold(value: Float) {
        prefs.edit().putFloat(KEY_THRESHOLD, value.coerceIn(0.1f, 0.95f)).apply()
    }

    // -------------------------------------------------------------------------
    // Active camera  ("back" | "front" | "ip")
    // -------------------------------------------------------------------------

    fun getActiveCamera(): String = prefs.getString(KEY_CAMERA, CAMERA_BACK) ?: CAMERA_BACK

    fun setActiveCamera(id: String) {
        prefs.edit().putString(KEY_CAMERA, id).apply()
    }

    // -------------------------------------------------------------------------
    // IP camera settings
    // -------------------------------------------------------------------------

    fun getIpUrl(): String = prefs.getString(KEY_IP_URL, "") ?: ""

    fun setIpUrl(url: String) {
        prefs.edit().putString(KEY_IP_URL, url.trim()).apply()
    }

    fun getIpUser(): String = prefs.getString(KEY_IP_USER, "") ?: ""

    fun setIpUser(user: String) {
        prefs.edit().putString(KEY_IP_USER, user.trim()).apply()
    }

    fun getIpPass(): String = prefs.getString(KEY_IP_PASS, "") ?: ""

    fun setIpPass(pass: String) {
        prefs.edit().putString(KEY_IP_PASS, pass).apply()
    }

    /**
     * Builds the full RTSP URL with credentials injected if provided.
     * Input:  rtsp://192.168.1.1:554/stream  +  user="admin"  +  pass="1234"
     * Output: rtsp://admin:1234@192.168.1.1:554/stream
     */
    fun buildAuthenticatedUrl(): String {
        val url  = getIpUrl()
        val user = getIpUser()
        val pass = getIpPass()
        if (url.isBlank()) return ""
        if (user.isBlank()) return url

        // Inject credentials after the scheme (rtsp:// or http://)
        val schemeEnd = url.indexOf("://")
        if (schemeEnd == -1) return url
        val scheme = url.substring(0, schemeEnd + 3)       // "rtsp://"
        val rest   = url.substring(schemeEnd + 3)           // "192.168.1.1:554/stream"
        return "$scheme${user}:${pass}@${rest}"
    }

    companion object {
        private const val PREFS_NAME      = "person_counter_settings"
        private const val KEY_THRESHOLD   = "threshold"
        private const val KEY_CAMERA      = "camera"
        private const val KEY_IP_URL      = "ip_url"
        private const val KEY_IP_USER     = "ip_user"
        private const val KEY_IP_PASS     = "ip_pass"

        const val CAMERA_BACK  = "back"
        const val CAMERA_FRONT = "front"
        const val CAMERA_IP    = "ip"

        const val DEFAULT_THRESHOLD = 0.5f
    }
}
