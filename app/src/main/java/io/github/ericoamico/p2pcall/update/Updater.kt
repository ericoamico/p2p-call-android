package io.github.ericoamico.p2pcall.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import io.github.ericoamico.p2pcall.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Release publicada no GitHub com o P2PCall.apk. */
data class ReleaseInfo(val version: String, val code: Int, val url: String, val size: Long, val sha256: String?)

/**
 * Atualização automática: compara a versão instalada com a última release do GitHub
 * (tag v1.0.N, N = versionCode), baixa o APK conferindo tamanho e SHA-256 e o instala
 * pelo instalador do Android.
 */
object Updater {
    private const val ASSET_NAME = "P2PCall.apk"

    /** Mensagens do instalador (falha, cancelado) para a interface. */
    val installMessage = MutableStateFlow<String?>(null)

    suspend fun check(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val conn = (URL(BuildConfig.RELEASES_API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "P2PCall-Android")
        }
        try {
            if (conn.responseCode != 200) return@withContext null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val version = json.getString("tag_name").removePrefix("v")
            val code = version.substringAfterLast('.').toIntOrNull() ?: return@withContext null
            if (code <= BuildConfig.VERSION_CODE) return@withContext null

            val assets = json.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.getString("name") != ASSET_NAME) continue
                val digest = asset.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
                return@withContext ReleaseInfo(
                    version, code, asset.getString("browser_download_url"), asset.getLong("size"), digest,
                )
            }
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Baixa o APK para o cache. [onProgress] recebe de 0 a 1. */
    suspend fun download(context: Context, release: ReleaseInfo, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val file = File(context.cacheDir, "update.apk")
            val digest = MessageDigest.getInstance("SHA-256")
            val conn = (URL(release.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "P2PCall-Android")
            }
            try {
                if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
                var total = 0L
                conn.inputStream.use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            total += n
                            if (release.size > 0) onProgress(total.toFloat() / release.size)
                        }
                    }
                }
                if (total != release.size) throw IOException("tamanho diferente do esperado")
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (release.sha256 != null && !hash.equals(release.sha256, ignoreCase = true)) {
                    throw IOException("arquivo corrompido")
                }
                file
            } catch (e: Exception) {
                file.delete()
                throw e
            } finally {
                conn.disconnect()
            }
        }

    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    /** Entrega o APK ao instalador; o Android pede a confirmação. */
    suspend fun install(context: Context, apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("P2PCall.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallReceiver::class.java)
            val pending = PendingIntent.getBroadcast(
                context, sessionId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
    }
}
