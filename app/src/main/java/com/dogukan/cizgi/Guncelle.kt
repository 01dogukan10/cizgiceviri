package com.dogukan.cizgi

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File

data class Surum(val kod: Int, val ad: String, val url: String, val not: String)

/** GitHub Releases üzerinden sürüm kontrolü, indirme ve kurulum. */
object Guncelle {
    const val TABAN = 100 // app/build.gradle.kts'teki versionCode = TABAN + yapı numarası ile aynı olmalı

    fun mevcutAd(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""

    private fun mevcutKod(ctx: Context): Long {
        val p = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()
    }

    fun yeniMi(ctx: Context, s: Surum) = TABAN + s.kod > mevcutKod(ctx)

    /** Deponun son sürümünü okur. Depo herkese açık olmalı; erişilemezse null. */
    fun sonSurum(): Surum? {
        val repo = BuildConfig.REPO
        if (repo.isEmpty()) return null
        val istek = Request.Builder().url("https://api.github.com/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json").build()
        Net.http.newCall(istek).execute().use { r ->
            if (!r.isSuccessful) return null
            val j = JSONObject(r.body!!.string())
            val kod = j.getString("tag_name").filter { it.isDigit() }.toIntOrNull() ?: return null
            val a = j.getJSONArray("assets")
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                if (o.getString("name").endsWith(".apk"))
                    return Surum(kod, j.optString("name", "v$kod"), o.getString("browser_download_url"), j.optString("body", ""))
            }
        }
        return null
    }

    fun kurabilir(ctx: Context) = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun izinAc(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun indir(ctx: Context, s: Surum): File {
        val d = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val f = File(d, "guncelle.apk")
        Net.http.newCall(Request.Builder().url(s.url).build()).execute().use { r ->
            if (!r.isSuccessful) error("İndirilemedi (HTTP ${r.code})")
            r.body!!.byteStream().use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        }
        return f
    }

    /** Sistem kurulum ekranını açar; kullanıcı orada ayrıca onaylar. */
    fun kur(ctx: Context, f: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun bildir(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel("guncelle", "Güncellemeler", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, "guncelle")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Çizgi Çevirici: yeni sürüm var")
            .setContentText("Güncellemek için dokun")
            .setContentIntent(pi).setAutoCancel(true).build()
        try { nm.notify(1, n) } catch (_: SecurityException) { }
    }
}

/** Yaklaşık saat başı, internet varken çalışır; yeni sürüm bulursa bildirim gösterir (kurmaz). */
class GuncelleIsci(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        try {
            val s = withContext(Dispatchers.IO) { Guncelle.sonSurum() } ?: return Result.success()
            val depo = Depo(applicationContext)
            if (Guncelle.yeniMi(applicationContext, s) && depo.bildirilen != s.kod) {
                depo.bildirilen = s.kod
                Guncelle.bildir(applicationContext)
            }
        } catch (_: Exception) { }
        return Result.success()
    }
}
