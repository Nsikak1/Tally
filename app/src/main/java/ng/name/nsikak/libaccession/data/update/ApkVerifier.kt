package ng.name.nsikak.libaccession.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

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
        if (!apkFile.exists()) return false

        if (!expectedSha256.isNull_or_empty()) {
            val calculatedHash = calculateSha256(apkFile)
            if (!calculatedHash.equals(expectedSha256?.trim(), ignoreCase = true)) {
                return false
            }
        }

        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

        val archiveInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, flags) ?: return false

        // Check package name matches current app
        if (archiveInfo.packageName != context.packageName) {
            return false
        }

        // Check signatures match current running package
        val installedPkg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, flags)
        }

        val archiveSigs = getSignatures(archiveInfo)
        val installedSigs = getSignatures(installedPkg)

        if (archiveSigs.isEmpty() || installedSigs.isEmpty()) return false

        return archiveSigs.any { archiveSig -> installedSigs.any { it.contentEquals(archiveSig) } }
    }

    private fun getSignatures(pkgInfo: PackageInfo): List<ByteArray> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = pkgInfo.signingInfo ?: return emptyList()
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners.map { it.toByteArray() }
            } else {
                signingInfo.signingCertificateHistory.map { it.toByteArray() }
            }
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.signatures?.map { it.toByteArray() } ?: emptyList()
        }
    }

    private fun String?.isNull_or_empty(): Boolean = this == null || this.trim().isEmpty()
}
