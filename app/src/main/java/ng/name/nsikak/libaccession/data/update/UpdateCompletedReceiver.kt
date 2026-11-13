package ng.name.nsikak.libaccession.data.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class UpdateCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            // Clean up cached update APKs and patch files
            val updateDir = context.cacheDir
            updateDir?.listFiles()?.forEach { file ->
                if (file.name.endsWith(".patch") || file.name.endsWith(".apk") || file.name.startsWith("tally_update")) {
                    file.delete()
                }
            }
        } catch (_: Exception) {
            // Ignore cleanup failures
        }
    }
}
