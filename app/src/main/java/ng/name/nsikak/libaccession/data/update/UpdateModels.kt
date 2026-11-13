package ng.name.nsikak.libaccession.data.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PatchInfo(
    @SerialName("fromVersionCode") val fromVersionCode: Long,
    @SerialName("patchUrl") val patchUrl: String,
    @SerialName("patchSha256") val patchSha256: String,
    @SerialName("patchSizeBytes") val patchSizeBytes: Long
)

@Serializable
data class UpdateInfoResponse(
    @SerialName("latestVersionCode") val latestVersionCode: Long,
    @SerialName("latestVersionName") val latestVersionName: String,
    @SerialName("minSupportedVersionCode") val minSupportedVersionCode: Long = 1,
    @SerialName("releaseNotes") val releaseNotes: String = "",
    @SerialName("fullApkUrl") val fullApkUrl: String,
    @SerialName("fullApkSha256") val fullApkSha256: String,
    @SerialName("fullApkSizeBytes") val fullApkSizeBytes: Long = 0,
    @SerialName("patches") val patches: List<PatchInfo> = emptyList()
)

sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data object UpToDate : UpdateStatus
    data class UpdateAvailable(
        val updateInfo: UpdateInfoResponse,
        val availablePatch: PatchInfo?
    ) : UpdateStatus
    data class Downloading(val progressPercent: Int, val bytesDownloaded: Long, val totalBytes: Long) : UpdateStatus
    data class Patching(val statusText: String) : UpdateStatus
    data class ReadyToInstall(val apkPath: String, val isDeltaPatch: Boolean) : UpdateStatus
    data class Error(val message: String) : UpdateStatus
}
