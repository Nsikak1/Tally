package ng.name.nsikak.libaccession.data.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import ng.name.nsikak.libaccession.data.api.ApiFactory
import ng.name.nsikak.libaccession.data.settings.ServerConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

class UpdateRepository(
    private val context: Context,
    private val apiFactory: ApiFactory
) {

    private val _updateStatus = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val updateStatus: StateFlow<UpdateStatus> = _updateStatus.asStateFlow()

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private fun createUpdateApi(config: ServerConfig): UpdateApi {
        val client = apiFactory.httpClient.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(config.baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(UpdateApi::class.java)
    }

    fun getCurrentVersionCode(): Long {
        return try {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0)).longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, 0).versionCode.toLong()
            }
        } catch (_: Exception) {
            1L
        }
    }

    fun getCurrentVersionName(): String {
        return try {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0)).versionName ?: "0.0.0"
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
            }
        } catch (_: Exception) {
            "0.0.0"
        }
    }

    suspend fun checkForUpdates(serverConfig: ServerConfig): UpdateStatus = withContext(Dispatchers.IO) {
        _updateStatus.value = UpdateStatus.Checking
        try {
            val api = createUpdateApi(serverConfig)
            val updateInfo = api.checkUpdate()
            val currentCode = getCurrentVersionCode()

            if (updateInfo.latestVersionCode > currentCode) {
                val matchingPatch = updateInfo.patches.firstOrNull { it.fromVersionCode == currentCode }
                val status = UpdateStatus.UpdateAvailable(
                    updateInfo = updateInfo,
                    availablePatch = matchingPatch
                )
                _updateStatus.value = status
                status
            } else {
                _updateStatus.value = UpdateStatus.UpToDate
                UpdateStatus.UpToDate
            }
        } catch (e: Exception) {
            val error = UpdateStatus.Error("Failed to check for updates: ${e.localizedMessage ?: "Network error"}")
            _updateStatus.value = error
            error
        }
    }

    suspend fun downloadAndPrepareUpdate(
        serverConfig: ServerConfig,
        updateInfo: UpdateInfoResponse,
        patchInfo: PatchInfo?
    ): UpdateStatus = withContext(Dispatchers.IO) {
        val cacheDir = context.cacheDir
        val currentCode = getCurrentVersionCode()

        val api = createUpdateApi(serverConfig)

        if (patchInfo != null) {
            // Delta Patch Flow
            _updateStatus.value = UpdateStatus.Downloading(0, 0, patchInfo.patchSizeBytes)
            val patchFile = File(cacheDir, "patch-v$currentCode-to-v${updateInfo.latestVersionCode}.patch")
            val patchedApkFile = File(cacheDir, "tally-v${updateInfo.latestVersionCode}-delta.apk")

            try {
                val responseBody = api.downloadFile(patchInfo.patchUrl)
                saveStreamToFile(responseBody, patchFile, patchInfo.patchSizeBytes) { progress, downloaded, total ->
                    _updateStatus.value = UpdateStatus.Downloading(progress, downloaded, total)
                }

                _updateStatus.value = UpdateStatus.Patching("Applying delta patch to installed app...")
                val sourceApk = File(context.applicationInfo.sourceDir)

                val patchSuccess = DeltaPatcher.applyPatch(sourceApk, patchFile, patchedApkFile)
                patchFile.delete()

                if (patchSuccess && ApkVerifier.verifyApk(context, patchedApkFile, updateInfo.fullApkSha256)) {
                    val ready = UpdateStatus.ReadyToInstall(patchedApkFile.absolutePath, isDeltaPatch = true)
                    _updateStatus.value = ready
                    return@withContext ready
                } else {
                    patchedApkFile.delete()
                    // Patch reconstruction failed or verification failed -> Fallback to Full APK download
                }
            } catch (_: Exception) {
                patchFile.delete()
                patchedApkFile.delete()
            }
        }

        // Full APK Download Flow
        _updateStatus.value = UpdateStatus.Downloading(0, 0, updateInfo.fullApkSizeBytes)
        val fullApkFile = File(cacheDir, "tally-v${updateInfo.latestVersionCode}-full.apk")

        try {
            val responseBody = api.downloadFile(updateInfo.fullApkUrl)
            saveStreamToFile(responseBody, fullApkFile, updateInfo.fullApkSizeBytes) { progress, downloaded, total ->
                _updateStatus.value = UpdateStatus.Downloading(progress, downloaded, total)
            }

            _updateStatus.value = UpdateStatus.Patching("Verifying APK integrity...")
            if (ApkVerifier.verifyApk(context, fullApkFile, updateInfo.fullApkSha256)) {
                val ready = UpdateStatus.ReadyToInstall(fullApkFile.absolutePath, isDeltaPatch = false)
                _updateStatus.value = ready
                ready
            } else {
                fullApkFile.delete()
                val error = UpdateStatus.Error("APK verification failed: Hash mismatch or untrusted signature.")
                _updateStatus.value = error
                error
            }
        } catch (e: Exception) {
            fullApkFile.delete()
            val error = UpdateStatus.Error("Failed to download full update: ${e.localizedMessage}")
            _updateStatus.value = error
            error
        }
    }

    fun installUpdate(apkPath: String): Boolean {
        val file = File(apkPath)
        return AppInstaller.installApk(context, file)
    }

    fun canInstallPackages(): Boolean = AppInstaller.canInstallPackages(context)

    fun openInstallPermissionSettings() = AppInstaller.openInstallPermissionSettings(context)

    private suspend fun saveStreamToFile(
        body: ResponseBody,
        outputFile: File,
        contentLengthHeader: Long,
        onProgress: (progressPercent: Int, downloaded: Long, total: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val totalLength = if (body.contentLength() > 0) body.contentLength() else contentLengthHeader
        val buffer = ByteArray(8192)
        var downloaded = 0L

        body.byteStream().use { input ->
            FileOutputStream(outputFile).use { output ->
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    downloaded += read
                    val progress = if (totalLength > 0) ((downloaded * 100) / totalLength).toInt() else 0
                    onProgress(progress, downloaded, totalLength)
                }
                output.flush()
            }
        }
    }
}
