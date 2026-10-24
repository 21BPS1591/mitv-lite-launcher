package dev.lite.launcher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val LATEST = "https://api.github.com/repos/21BPS1591/mitv-lite-launcher/releases/latest"
private const val ACTION_RESULT = "dev.lite.launcher.UPDATE_RESULT"

/** A newer release found on GitHub. */
class Update(val version: String, val apkUrl: String)

/** Checks GitHub Releases for a newer signed APK and installs it through the system package installer. */
object Updater {
    /** Blocking; call off the main thread. Returns null when up to date or the check fails. */
    fun check(context: Context): Update? = try {
        val json = JSONObject(get(LATEST).readBytes().toString(Charsets.UTF_8))
        val version = json.getString("tag_name").removePrefix("v")
        val assets = json.getJSONArray("assets")
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.getString("name").endsWith(".apk") }
        val current = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
        if (apk != null && isNewer(version, current)) Update(version, apk.getString("browser_download_url")) else null
    } catch (e: Exception) {
        null
    }

    /** Blocking; release notes GitHub holds for [version], or null when offline or none were written. */
    fun notes(version: String): String? = try {
        JSONObject(get("https://api.github.com/repos/21BPS1591/mitv-lite-launcher/releases/tags/v$version")
            .readBytes().toString(Charsets.UTF_8)).optString("body").trim().ifEmpty { null }
    } catch (e: Exception) {
        null
    }

    /** Blocking; streams the APK straight into an install session, then hands over to the system prompt. */
    fun install(context: Context, update: Update): Boolean = try {
        val installer = context.packageManager.packageInstaller
        val id = installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        installer.openSession(id).use { session ->
            get(update.apkUrl).use { input ->
                session.openWrite("update.apk", 0, -1).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val result = PendingIntent.getBroadcast(
                context, id, Intent(ACTION_RESULT).setPackage(context.packageName), PendingIntent.FLAG_UPDATE_CURRENT
            )
            session.commit(result.intentSender)
        }
        true
    } catch (e: Exception) {
        false
    }

    private fun get(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 30_000
        setRequestProperty("Accept", "application/vnd.github+json")
    }.inputStream

    private fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split('.').map { it.toIntOrNull() ?: 0 }
        val b = current.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}

/** Receives the install session result and surfaces the system confirmation screen. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1) != PackageInstaller.STATUS_PENDING_USER_ACTION) return
        @Suppress("DEPRECATION")
        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
        context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
