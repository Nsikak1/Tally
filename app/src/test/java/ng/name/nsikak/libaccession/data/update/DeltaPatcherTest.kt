package ng.name.nsikak.libaccession.data.update

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DeltaPatcherTest {

    @Test
    fun testRealApkPatchingIfAvailable() {
        val rootDir = File("..")
        val oldApk = File(rootDir, "updates/apks/tally-v0.8.0-vc9.apk")
        val newApk = File(rootDir, "updates/apks/tally-v0.9.0-vc10.apk")
        val patchFile = File(rootDir, "updates/patches/patch-v9-to-v10.patch")

        if (oldApk.exists() && newApk.exists() && patchFile.exists()) {
            val outputApk = File.createTempFile("reconstructed-", ".apk")
            try {
                val success = DeltaPatcher.applyPatch(oldApk, patchFile, outputApk)
                if (success) {
                    assertTrue("Reconstructed APK should exist and not be empty", outputApk.exists() && outputApk.length() > 0)
                }
            } finally {
                outputApk.delete()
            }
        }
    }
}
