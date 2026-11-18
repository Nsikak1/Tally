package ng.name.nsikak.libaccession.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

sealed interface VerificationResult {
    data object Success : VerificationResult
    data class Failure(val reason: String) : VerificationResult
}

object ApkVerifier {

    /** Calculates hex SHA-256 string for the given file. */
    fun calculateSha256(file: File): String {
        if (!file.exists()) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Verifies that [apkFile] has package name matching this app and is signed with
     * the same signing certificates.
     */
    fun verifyApk(context: Context, apkFile: File, expectedSha256: String? = null): Boolean {
        return verifyApkDetailed(context, apkFile, expectedSha256) is VerificationResult.Success
    }

    fun verifyApkDetailed(context: Context, apkFile: File, expectedSha256: String? = null): VerificationResult {
        if (!apkFile.exists()) {
            return VerificationResult.Failure("APK file does not exist.")
        }

        if (!expectedSha256.isNullOrBlank()) {
            val calculatedHash = calculateSha256(apkFile)
            if (!calculatedHash.equals(expectedSha256.trim(), ignoreCase = true)) {
                return VerificationResult.Failure(
                    "SHA-256 hash mismatch. Expected: ${expectedSha256.trim().take(12)}... Got: ${calculatedHash.take(12)}..."
                )
            }
        }

        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        } else {
            PackageManager.GET_SIGNATURES
        }

        val archiveInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
            ?: return VerificationResult.Failure("Failed to parse package info from APK archive.")

        archiveInfo.applicationInfo?.let { appInfo ->
            appInfo.sourceDir = apkFile.absolutePath
            appInfo.publicSourceDir = apkFile.absolutePath
        }

        if (archiveInfo.packageName != context.packageName) {
            return VerificationResult.Failure(
                "Package name mismatch. Expected: ${context.packageName}, Found: ${archiveInfo.packageName}"
            )
        }

        val installedPkg = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                pm.getPackageInfo(context.packageName, flags)
            }
        } catch (e: Exception) {
            return VerificationResult.Failure("Failed to retrieve installed package info: ${e.message}")
        }

        val archiveSigs = getSignatures(archiveInfo)
        val installedSigs = getSignatures(installedPkg)

        if (archiveSigs.isEmpty()) {
            return VerificationResult.Failure("No signing certificates found in update APK archive.")
        }

        if (installedSigs.isEmpty()) {
            return VerificationResult.Failure("No signing certificates found in installed app.")
        }

        val matches = archiveSigs.any { archiveSig -> installedSigs.any { it.contentEquals(archiveSig) } }
        return if (matches) {
            VerificationResult.Success
        } else {
            VerificationResult.Failure("Signing certificate mismatch between installed app and update APK.")
        }
    }

    private fun getSignatures(pkgInfo: PackageInfo): List<ByteArray> {
        val result = mutableListOf<ByteArray>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = pkgInfo.signingInfo
            if (signingInfo != null) {
                val sigs = if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory
                }
                if (sigs != null && sigs.isNotEmpty()) {
                    sigs.mapTo(result) { it.toByteArray() }
                }
            }
        }

        if (result.isEmpty()) {
            @Suppress("DEPRECATION")
            val legacySigs = pkgInfo.signatures
            if (legacySigs != null && legacySigs.isNotEmpty()) {
                legacySigs.mapTo(result) { it.toByteArray() }
            }
        }

        return result
    }
}
