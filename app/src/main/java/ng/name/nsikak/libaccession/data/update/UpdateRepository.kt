package ng.name.nsikak.libaccession.data.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import ng.name.nsikak.libaccession.data.api.ApiFactory
import ng.name.nsikak.libaccession.data.settings.ServerConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class UpdateRepository(
    private val context: Context,
    private val apiFactory: ApiFactory
) {

    private val _updateStatus = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val updateStatus: StateFlow<UpdateStatus> = _updateStatus.asStateFlow()

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private fun createUpdateApi(config: ServerConfig): UpdateApi {
        val client = apiFactory.httpClient.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        return Retrofit.Builder()
            .baseUrl(config.baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(UpdateApi::class.java)
    }

    private fun resolveDownloadUrl(config: ServerConfig, urlString: String): String {
        return try {
            val baseUrl = config.baseUrl.toHttpUrl()
            val targetUrl = urlString.toHttpUrlOrNull()
            if (targetUrl != null) {
                baseUrl.newBuilder()
                    .encodedPath(targetUrl.encodedPath)
                    .encodedQuery(targetUrl.encodedQuery)
                    .build()
                    .toString()
            } else {
                baseUrl.resolve(urlString)?.toString() ?: urlString
            }
        } catch (_: Exception) {
            urlString
        }
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
            val isNotFound = (e is HttpException) && (e.code() == 404)
            if (isNotFound) {
                _updateStatus.value = UpdateStatus.UpToDate
                UpdateStatus.UpToDate
            } else {
                val error = UpdateStatus.Error("Failed to check for updates: ${e.localizedMessage ?: "Network error"}")
                _updateStatus.value = error
                error
            }
        }
    }

    suspend fun downloadAndPrepareUpdate(
        serverConfig: ServerConfig,
        updateInfo: UpdateInfoResponse,
        patchInfo: PatchInfo?
    ): UpdateStatus = withContext(Dispatchers.IO) {
        val cacheDir = context.cacheDir
        val currentCode = getCurrentVersionCode()

        cleanupOldUpdateFiles(cacheDir, updateInfo.latestVersionCode)

        val api = createUpdateApi(serverConfig)

        if (patchInfo != null) {
            // Delta Patch Flow
            val patchFile = File(cacheDir, "patch-v$currentCode-to-v${updateInfo.latestVersionCode}.patch")
            val patchedApkFile = File(cacheDir, "tally-v${updateInfo.latestVersionCode}-delta.apk")
            _updateStatus.value = UpdateStatus.Downloading(
                progressPercent = if (patchFile.exists() && patchInfo.patchSizeBytes > 0) {
                    ((patchFile.length() * 100 / patchInfo.patchSizeBytes).toInt()).coerceIn(0, 100)
                } else 0,
                bytesDownloaded = if (patchFile.exists()) patchFile.length() else 0L,
                totalBytes = patchInfo.patchSizeBytes
            )

            var patchDownloadSuccess = false
            var patchDownloadError: Exception? = null

            try {
                patchDownloadSuccess = downloadFileWithResumeAndRetry(
                    api = api,
                    serverConfig = serverConfig,
                    urlString = patchInfo.patchUrl,
                    targetFile = patchFile,
                    expectedSizeBytes = patchInfo.patchSizeBytes,
                    expectedSha256 = patchInfo.patchSha256,
                    onProgress = { progress, downloaded, total ->
                        _updateStatus.value = UpdateStatus.Downloading(progress, downloaded, total)
                    }
                )
            } catch (e: Exception) {
                patchDownloadError = e
            }

            if (patchDownloadSuccess) {
                _updateStatus.value = UpdateStatus.Patching("Applying delta patch to installed app...")
                val sourceApk = File(context.applicationInfo.sourceDir)

                val patchSuccess = DeltaPatcher.applyPatch(sourceApk, patchFile, patchedApkFile)
                patchFile.delete()

                val patchVerification = if (patchSuccess) {
                    ApkVerifier.verifyApkDetailed(context, patchedApkFile, updateInfo.fullApkSha256)
                } else VerificationResult.Failure("Delta patch reconstruction failed.")

                if (patchVerification is VerificationResult.Success) {
                    val ready = UpdateStatus.ReadyToInstall(patchedApkFile.absolutePath, isDeltaPatch = true)
                    _updateStatus.value = ready
                    return@withContext ready
                } else {
                    patchedApkFile.delete()
                    // Patch reconstruction failed or verification failed -> Fallback to Full APK download
                }
            } else if (patchDownloadError is HttpException && patchDownloadError.code() == 404) {
                // Patch file missing on server (404), fallback to full APK
                patchFile.delete()
            } else if (patchDownloadError != null) {
                // Network error during patch download: do NOT fall back to full APK (which would also fail).
                // Keep partial patch file for resume.
                val error = UpdateStatus.Error("Failed to download patch: ${patchDownloadError.localizedMessage ?: "Network error"}")
                _updateStatus.value = error
                return@withContext error
            }
        }

        // Full APK Download Flow
        val fullApkFile = File(cacheDir, "tally-v${updateInfo.latestVersionCode}-full.apk")
        _updateStatus.value = UpdateStatus.Downloading(
            progressPercent = if (fullApkFile.exists() && updateInfo.fullApkSizeBytes > 0) {
                ((fullApkFile.length() * 100 / updateInfo.fullApkSizeBytes).toInt()).coerceIn(0, 100)
            } else 0,
            bytesDownloaded = if (fullApkFile.exists()) fullApkFile.length() else 0L,
            totalBytes = updateInfo.fullApkSizeBytes
        )

        try {
            val downloadSuccess = downloadFileWithResumeAndRetry(
                api = api,
                serverConfig = serverConfig,
                urlString = updateInfo.fullApkUrl,
                targetFile = fullApkFile,
                expectedSizeBytes = updateInfo.fullApkSizeBytes,
                expectedSha256 = updateInfo.fullApkSha256,
                onProgress = { progress, downloaded, total ->
                    _updateStatus.value = UpdateStatus.Downloading(progress, downloaded, total)
                }
            )

            if (downloadSuccess) {
                _updateStatus.value = UpdateStatus.Patching("Verifying APK integrity...")
                val fullVerification = ApkVerifier.verifyApkDetailed(context, fullApkFile, updateInfo.fullApkSha256)
                if (fullVerification is VerificationResult.Success) {
                    val ready = UpdateStatus.ReadyToInstall(fullApkFile.absolutePath, isDeltaPatch = false)
                    _updateStatus.value = ready
                    ready
                } else {
                    fullApkFile.delete()
                    val reason = (fullVerification as? VerificationResult.Failure)?.reason ?: "Verification failed."
                    val error = UpdateStatus.Error("APK verification failed: $reason")
                    _updateStatus.value = error
                    error
                }
            } else {
                val error = UpdateStatus.Error("Failed to download full update.")
                _updateStatus.value = error
                error
            }
        } catch (e: Exception) {
            val error = UpdateStatus.Error("Failed to download full update: ${e.localizedMessage ?: "Network error"}")
            _updateStatus.value = error
            error
        }
    }

    fun startBackgroundDownload(
        serverConfig: ServerConfig,
        updateInfo: UpdateInfoResponse,
        patchInfo: PatchInfo?
    ) {
        UpdateDownloadService.startDownload(context, serverConfig, updateInfo, patchInfo, json)
    }

    fun installUpdate(apkPath: String): Boolean {
        val file = File(apkPath)
        return AppInstaller.installApk(context, file)
    }

    fun canInstallPackages(): Boolean = AppInstaller.canInstallPackages(context)

    fun openInstallPermissionSettings() = AppInstaller.openInstallPermissionSettings(context)

    private suspend fun downloadFileWithResumeAndRetry(
        api: UpdateApi,
        serverConfig: ServerConfig,
        urlString: String,
        targetFile: File,
        expectedSizeBytes: Long,
        expectedSha256: String? = null,
        onProgress: (progressPercent: Int, downloaded: Long, total: Long) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val resolvedUrl = resolveDownloadUrl(serverConfig, urlString)
        var retryDelaySeconds = 2L
        val maxRetryDelaySeconds = 300L // 5 minutes cap
        var lastException: Exception? = null

        while (coroutineContext[Job]?.isActive != false) {
            try {
                if (targetFile.exists()) {
                    val currentLen = targetFile.length()
                    if (expectedSizeBytes > 0 && currentLen == expectedSizeBytes) {
                        if (!expectedSha256.isNullOrBlank()) {
                            val hash = ApkVerifier.calculateSha256(targetFile)
                            if (hash.equals(expectedSha256.trim(), ignoreCase = true)) {
                                onProgress(100, expectedSizeBytes, expectedSizeBytes)
                                return@withContext true
                            } else {
                                targetFile.delete()
                            }
                        } else {
                            onProgress(100, expectedSizeBytes, expectedSizeBytes)
                            return@withContext true
                        }
                    } else if (expectedSizeBytes > 0 && currentLen > expectedSizeBytes) {
                        targetFile.delete()
                    }
                }

                val existingLength = if (targetFile.exists()) targetFile.length() else 0L
                val rangeHeader = if (existingLength > 0) "bytes=$existingLength-" else null

                val response = api.downloadFile(resolvedUrl, rangeHeader)
                if (!response.isSuccessful) {
                    val code = response.code()
                    if (code == 416 && existingLength > 0) {
                        targetFile.delete()
                        continue
                    }
                    if (code == 404) {
                        throw HttpException(response)
                    }
                    throw IOException("HTTP download failed with status code $code: ${response.message()}")
                }

                val responseBody = response.body() ?: throw IOException("Empty download response body")
                val isPartial = response.code() == 206
                val append = isPartial && existingLength > 0

                val startDownloaded = if (append) existingLength else 0L
                val contentLength = responseBody.contentLength()
                val totalBytes = when {
                    isPartial -> {
                        val contentRangeHeader = response.headers()["Content-Range"]
                        val parsedTotal = parseTotalFromContentRange(contentRangeHeader)
                        if (parsedTotal > 0) parsedTotal else if (contentLength > 0) startDownloaded + contentLength else expectedSizeBytes
                    }
                    contentLength > 0 -> contentLength
                    else -> expectedSizeBytes
                }

                saveResponseBodyToFile(
                    body = responseBody,
                    outputFile = targetFile,
                    append = append,
                    startDownloaded = startDownloaded,
                    totalBytes = totalBytes,
                    onProgress = { progress, downloaded, total ->
                        // Successful chunk receive -> reset retry timer back to 2 seconds
                        retryDelaySeconds = 2L
                        onProgress(progress, downloaded, total)
                    }
                )

                val finalLen = targetFile.length()
                if (expectedSizeBytes > 0 && finalLen < expectedSizeBytes) {
                    delay(retryDelaySeconds * 1000L)
                    retryDelaySeconds = (retryDelaySeconds + 2L).coerceAtMost(maxRetryDelaySeconds)
                    continue
                }

                if (!expectedSha256.isNullOrBlank()) {
                    val actualHash = ApkVerifier.calculateSha256(targetFile)
                    if (!actualHash.equals(expectedSha256.trim(), ignoreCase = true)) {
                        targetFile.delete()
                        throw IOException("Downloaded file SHA-256 hash mismatch.")
                    }
                }

                return@withContext true
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                lastException = e
                if (e is HttpException && e.code() == 404) {
                    throw e
                }

                val currentBytes = if (targetFile.exists()) targetFile.length() else 0L
                val pct = if (expectedSizeBytes > 0) ((currentBytes * 100 / expectedSizeBytes).toInt()).coerceIn(0, 100) else 0
                _updateStatus.value = UpdateStatus.Downloading(
                    progressPercent = pct,
                    bytesDownloaded = currentBytes,
                    totalBytes = expectedSizeBytes
                )

                delay(retryDelaySeconds * 1000L)
                retryDelaySeconds = (retryDelaySeconds + 2L).coerceAtMost(maxRetryDelaySeconds)
            }
        }

        if (lastException != null) throw lastException
        return@withContext false
    }

    internal fun parseTotalFromContentRange(header: String?): Long {
        if (header.isNullOrBlank()) return -1L
        return try {
            val slashIndex = header.indexOf('/')
            if (slashIndex != -1) {
                val totalStr = header.substring(slashIndex + 1).trim()
                if (totalStr != "*") totalStr.toLong() else -1L
            } else -1L
        } catch (_: Exception) {
            -1L
        }
    }

    private fun cleanupOldUpdateFiles(cacheDir: File, targetVersionCode: Long) {
        try {
            val files = cacheDir.listFiles() ?: return
            for (file in files) {
                val name = file.name
                if (name.startsWith("patch-v") && name.endsWith(".patch")) {
                    if (!name.endsWith("-to-v$targetVersionCode.patch")) {
                        file.delete()
                    }
                } else if (name.startsWith("tally-v") && name.endsWith(".apk")) {
                    if (!name.startsWith("tally-v$targetVersionCode-")) {
                        file.delete()
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore cleanup errors
        }
    }

    private suspend fun saveResponseBodyToFile(
        body: ResponseBody,
        outputFile: File,
        append: Boolean,
        startDownloaded: Long,
        totalBytes: Long,
        onProgress: (progressPercent: Int, downloaded: Long, total: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val buffer = ByteArray(8192)
        var downloaded = startDownloaded

        body.byteStream().use { input ->
            FileOutputStream(outputFile, append).use { output ->
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    downloaded += read
                    val progress = if (totalBytes > 0) ((downloaded * 100 / totalBytes).toInt()).coerceIn(0, 100) else 0
                    onProgress(progress, downloaded, totalBytes)
                }
                output.flush()
            }
        }
    }
}
