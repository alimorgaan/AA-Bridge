package dev.caraalink.settings

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * Typed reads over the preferences declared in res/xml/root_preferences.xml.
 * Nothing in the capture/protocol pipeline consumes these yet — they're wired
 * up as that code lands (see claude/roadmap.md Phase 2/3). Keys here must
 * stay in sync with root_preferences.xml.
 */
object AppSettings {

    enum class ResolutionMode { AUTO, CUSTOM }
    enum class UsbRoleOverride { AUTO, FORCE_ACCESSORY, FORCE_HOST }
    enum class AaAudioRoute { BT_PREFERRED, DEVICE_ONLY, PHONE_DIRECT }
    enum class CarOrientation { AUTO, LANDSCAPE, PORTRAIT }
    enum class AaFillMode { FIT, FILL }
    enum class UpdateChannel { STABLE, DEV }

    private fun prefs(context: Context) =
        PreferenceManager.getDefaultSharedPreferences(context)

    fun setupComplete(context: Context): Boolean = prefs(context).getBoolean("setup_complete", false)
    fun setSetupComplete(context: Context, value: Boolean) = prefs(context).edit().putBoolean("setup_complete", value).apply()
    fun devModeConfirmed(context: Context): Boolean = prefs(context).getBoolean("dev_mode_confirmed", false)
    fun setDevModeConfirmed(context: Context, value: Boolean) = prefs(context).edit().putBoolean("dev_mode_confirmed", value).apply()
    fun carConnectedOnce(context: Context): Boolean = prefs(context).getBoolean("car_connected_once", false)
    fun setCarConnectedOnce(context: Context) = prefs(context).edit().putBoolean("car_connected_once", true).apply()

    fun resolutionMode(context: Context): ResolutionMode =
        when (prefs(context).getString("video_resolution_mode", "auto")) {
            "custom" -> ResolutionMode.CUSTOM
            else -> ResolutionMode.AUTO
        }

    /** Only meaningful when [resolutionMode] is CUSTOM; null falls back to AUTO behavior. */
    fun customResolution(context: Context): Pair<Int, Int>? {
        val w = prefs(context).getString("video_resolution_custom_width", null)?.toIntOrNull()
        val h = prefs(context).getString("video_resolution_custom_height", null)?.toIntOrNull()
        return if (w != null && h != null) w to h else null
    }

    /**
     * Shape of the actual head unit panel -- picks which AA resolution set
     * is valid. Defaults to AUTO, which goes by what the car itself reported
     * in its `CONFIG_CAPTURE` ([dev.caraalink.link.CarScreenInfo]); the
     * manual landscape/portrait options stay for when that hasn't happened
     * yet (e.g. the on-phone preview before ever connecting to a car) or the
     * car is wrong/unavailable.
     */
    fun carOrientation(context: Context): CarOrientation =
        when (prefs(context).getString("car_orientation", "auto")) {
            "landscape" -> CarOrientation.LANDSCAPE
            "portrait" -> CarOrientation.PORTRAIT
            else -> CarOrientation.AUTO
        }

    fun frameRate(context: Context): Int =
        prefs(context).getString("video_frame_rate", "60")?.toIntOrNull() ?: 60

    fun videoBitrateKbps(context: Context): Int =
        prefs(context).getString("video_bitrate_kbps", "6000")?.toIntOrNull() ?: 6000

    /**
     * Auto-detect replicates the exact formula CarDisplay.attach() already
     * uses for the car's own virtual display -- scaled off the video's own
     * [width]x[height], not the phone's screen density (that sized the car
     * home screen correctly; the phone's own density has nothing to do with
     * how big things render on the car's panel). Manual is a straight AA
     * `density` value.
     */
    fun videoDpi(context: Context, width: Int, height: Int): Int {
        if (videoDpiAuto(context)) {
            return (minOf(width, height) / 720f * 213).toInt().coerceIn(120, 320)
        }
        return manualVideoDpi(context)
    }

    /** True if the auto-DPI formula is in use; false means a manual value was set. */
    fun videoDpiAuto(context: Context): Boolean = prefs(context).getBoolean("video_dpi_auto", true)

    fun manualVideoDpi(context: Context): Int =
        prefs(context).getString("video_dpi_manual", "160")?.toIntOrNull() ?: 160

    fun audioSampleRateHz(context: Context): Int =
        prefs(context).getString("audio_sample_rate", "48000")?.toIntOrNull() ?: 48000

    fun audioStereo(context: Context): Boolean =
        prefs(context).getBoolean("audio_stereo", true)

    fun autoReconnectUsb(context: Context): Boolean =
        prefs(context).getBoolean("auto_reconnect_usb", true)

    /** Debug-only. USB role is CONFIRMED (phone = accessory) — see claude/roadmap.md. */
    fun usbRoleOverride(context: Context): UsbRoleOverride =
        when (prefs(context).getString("usb_role_override", "auto")) {
            "accessory" -> UsbRoleOverride.FORCE_ACCESSORY
            "host" -> UsbRoleOverride.FORCE_HOST
            else -> UsbRoleOverride.AUTO
        }

    fun verboseLogging(context: Context): Boolean =
        prefs(context).getBoolean("debug_verbose_logging", false)

    fun debugOverlay(context: Context): Boolean =
        prefs(context).getBoolean("debug_overlay", false)

    fun autoSaveCrashLog(context: Context): Boolean =
        prefs(context).getBoolean("auto_save_crash_log", true)

    /**
     * When on (default), `AaServiceDiscovery.configFromSettings()` computes
     * `width_margin`/`height_margin` automatically from the car's real
     * reported screen size (CarScreenInfo) vs. the chosen AA resolution's
     * aspect ratio, instead of leaving it at 0 (plain letterbox/pillarbox)
     * or requiring a manually-typed debug value. The per-side-vs-total
     * semantics of the wire field are still unconfirmed (claude/roadmap.md)
     * -- turn this off if a live test shows the crop going the wrong way.
     */
    fun aaAutoCrop(context: Context): Boolean =
        prefs(context).getBoolean("aa_auto_crop", true)

    /**
     * Separate from Custom resolution (which is an exact owner-typed WxH):
     * force a 720p tier even when the panel-fit Auto policy would otherwise
     * choose 1080p. Auto already chooses 720p for a known 1920x720 ultrawide
     * panel. Custom resolution still wins outright when set.
     */
    fun videoDownscale720p(context: Context): Boolean =
        prefs(context).getBoolean("video_downscale_720p", false)

    /**
     * AA only offers 9 fixed resolutions (see AaServiceDiscovery), none of
     * which matches CarbitLink's own 2.67:1 panel (confirmed: 1920x720) --
     * so AA's video can never exactly fill that screen no matter which of
     * the 9 is picked. FIT (default) letterboxes/pillarboxes so nothing is
     * ever cropped; FILL scales up to cover the whole panel at the cost of
     * clipping some of AA's UI off the top/bottom or sides.
     */
    fun aaFillMode(context: Context): AaFillMode =
        when (prefs(context).getString("aa_fill_mode", "fit")) {
            "fill" -> AaFillMode.FILL
            else -> AaFillMode.FIT
        }

    fun aaAudioRoute(context: Context): AaAudioRoute =
        when (prefs(context).getString("aa_audio_route", "bt_preferred")) {
            "device_only" -> AaAudioRoute.DEVICE_ONLY
            "phone_direct" -> AaAudioRoute.PHONE_DIRECT
            else -> AaAudioRoute.BT_PREFERRED
        }

    fun updateChannel(context: Context): UpdateChannel =
        when (prefs(context).getString("update_channel", "stable")) {
            "dev" -> UpdateChannel.DEV
            else -> UpdateChannel.STABLE
        }

    fun updateAutoCheck(context: Context): Boolean =
        prefs(context).getBoolean("update_auto_check", true)
}
