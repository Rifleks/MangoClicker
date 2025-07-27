package rifleks.clicker

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream

class UpdateService(
    private val context: android.content.Context,
    private val listener: UpdateInstallListener? = null
) : Service() {

    interface UpdateInstallListener {
        fun onInstallCompleted()
        fun onInstallFailed()
        fun onProgressUpdate(percent: Int, downloaded: Long, total: Long)
        fun onDownloadComplete(file: File)
    }

    var latestDownloadUrl: String = ""
    var latestVersionName: String = ""

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient()

    fun checkForUpdateAndInstall(onResult: (Boolean, Long, String, String, Long) -> Unit) {
        scope.launch {
            try {
                // Получаем текущую версию приложения
                val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(
                        context.packageName,
                        PackageManager.PackageInfoFlags.of(0)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0)
                }

                val currentVersionName = packageInfo.versionName ?: "0.0"
                val currentVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo.versionCode.toLong()
                }

                // Запрос к GitHub Releases API
                val apiUrl = "https://api.github.com/repos/Rifleks/MangoClicker/releases"
                val request = Request.Builder().url(apiUrl).build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@launch

                val body = response.body.string()
                val releases = JSONArray(body)
                if (releases.length() == 0) return@launch

                val latest = releases.getJSONObject(0)
                val assets = latest.getJSONArray("assets")
                if (assets.length() == 0) return@launch

                // Ищем .apk-файл среди ассетов
                val apkAsset = (0 until assets.length())
                    .map { assets.getJSONObject(it) }
                    .firstOrNull { it.getString("name").endsWith(".apk") }

                if (apkAsset == null) {
                    Log.w("UpdateCheck", "No APK asset found in GitHub release")
                    return@launch
                }

                val fileName = apkAsset.getString("name") // Например: MangoClicker_1.4_6.apk
                val downloadUrl = apkAsset.getString("browser_download_url")
                val size = apkAsset.optLong("size", 0L)

                // Парсим версию из имени файла
                val regex = Regex("""MangoClicker_(\d+\.\d+)_(\d+)\.apk""")
                val match = regex.find(fileName)

                val (remoteVersionName, remoteVersionCode) = if (match != null) {
                    val (name, code) = match.destructured
                    name to code.toLong()
                } else {
                    Log.w("UpdateCheck", "Filename format does not match regex: $fileName")
                    "0.0" to 0L
                }

                Log.d("UpdateCheck", "Current: $currentVersionName ($currentVersionCode), Remote: $remoteVersionName ($remoteVersionCode)")

                // Сохраняем найденную версию
                latestVersionName = remoteVersionName
                latestDownloadUrl = downloadUrl

                // Нужно ли обновление?
                val shouldUpdate = isUpdateRequired(currentVersionName, currentVersionCode, remoteVersionName, remoteVersionCode)

                withContext(Dispatchers.Main) {
                    onResult(shouldUpdate, size, downloadUrl, remoteVersionName, remoteVersionCode)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    onResult(false, 0L, "", "0.0", 0L)
                }
            }
        }
    }

    private fun isUpdateRequired(currentVersionName: String, currentCode: Long, remoteVersionName: String, remoteCode: Long): Boolean {
        val (currentBase, _) = extractVersionParts(currentVersionName)
        val (remoteBase, _) = extractVersionParts(remoteVersionName)

        return if (currentBase != remoteBase) {
            compareVersions(currentBase, remoteBase) < 0
        } else {
            remoteCode > currentCode
        }
    }

    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split(".").mapNotNull { it.toIntOrNull() }
        val parts2 = v2.split(".").mapNotNull { it.toIntOrNull() }
        val maxLength = maxOf(parts1.size, parts2.size)

        for (i in 0 until maxLength) {
            val p1 = parts1.getOrNull(i) ?: 0
            val p2 = parts2.getOrNull(i) ?: 0
            if (p1 != p2) return p1 - p2
        }
        return 0
    }

    fun downloadAndInstallUpdate(downloadUrl: String, versionName: String, versionCode: Long) {
        scope.launch {
            var file: File? = null

            try {
                val request = Request.Builder().url(downloadUrl).build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@launch

                val inputStream = response.body.byteStream()
                val fileName = "MangoClicker_${versionName}_${versionCode}.apk"
                file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
                val output = FileOutputStream(file)

                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var bytesRead: Int
                var totalBytesRead = 0L
                val totalBytes = response.body.contentLength()

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead
                    val progressPercent = if (totalBytes > 0) (100 * totalBytesRead / totalBytes).toInt() else -1
                    withContext(Dispatchers.Main) {
                        listener?.onProgressUpdate(progressPercent, totalBytesRead, totalBytes)
                    }
                }

                output.close()
                inputStream.close()

                withContext(Dispatchers.Main) {
                    listener?.onProgressUpdate(100, totalBytesRead, totalBytes)
                    file.let { listener?.onDownloadComplete(it) } // передаём файл в MainActivity
                    listener?.onInstallCompleted()
                    installApk(file) // установка
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    listener?.onInstallFailed()
                    Toast.makeText(context, Translation.translate("update_install_general_failed"), Toast.LENGTH_SHORT).show()
                    file?.let { listener?.onDownloadComplete(it) } // всё ещё отправим, если был создан
                }
            }
        }
    }

    @Suppress("QueryPermissionsNeeded")
    fun installApk(apkFile: File) {
        try {
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }

            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                listener?.onInstallCompleted()
            } else {
                listener?.onInstallFailed()
                Toast.makeText(context, "Не найдено приложение для установки", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            listener?.onInstallFailed()
            Toast.makeText(context, "Ошибка установки: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onBind(intent: Intent?) = null

    fun cancelDownload() {
        scope.coroutineContext.cancelChildren()
    }

    fun extractVersionParts(version: String): Pair<String, Int> {
        val regex = Regex("""([\d.]+)_(\d+)""")
        val match = regex.find(version)
        return if (match != null) {
            val (base, code) = match.destructured
            base to code.toInt()
        } else {
            version to 0
        }
    }
}
