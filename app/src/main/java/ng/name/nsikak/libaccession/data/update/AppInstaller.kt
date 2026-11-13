package ng.name.nsikak.libaccession.data.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream

object AppInstaller {

    /** Returns true if the app can request package installations (API 26+ check). */
    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /** Triggers the OS settings page to enable "Install unknown apps" permission for this app. */
    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    /** Installs [apkFile] using PackageInstaller (API 31+ silent/unattended session) or Intent fallback. */
    fun installApk(context: Context, apkFile: File): Boolean {
        if (!apkFile.exists()) return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && canInstallPackages(context)) {
            installViaPackageInstallerSession(context, apkFile)
        } else {
            installViaIntent(context, apkFile)
        }
    }

    private fun installViaPackageInstallerSession(context: Context, apkFile: File): Boolean {
        return try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(context.packageName)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }

            val sessionId = packageInstaller.createSession(params)
            packageInstaller.openSession(sessionId).use { session ->
                FileInputStream(apkFile).use { fis ->
                    session.openWrite("tally_update.apk", 0, apkFile.length()).use { out ->
                        fis.copyTo(out)
                        session.fsync(out)
                    }
                }

                val intent = Intent(context, UpdateCompletedReceiver::class.java).apply {
                    action = "ng.name.nsikak.libaccession.UPDATE_STATUS"
                }

                val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }

                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    intent,
                    pendingIntentFlags
                )

                session.commit(pendingIntent.intentSender)
            }
            true
        } catch (e: Exception) {
            // Fallback to intent if session fails
            installViaIntent(context, apkFile)
        }
    }

    private fun installViaIntent(context: Context, apkFile: File): Boolean {
        return try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}
