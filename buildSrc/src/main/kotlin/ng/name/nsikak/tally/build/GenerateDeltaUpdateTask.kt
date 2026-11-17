package ng.name.nsikak.tally.build

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.security.MessageDigest

open class GenerateDeltaUpdateTask : DefaultTask() {

    @get:Input
    @get:Optional
    var outputDirProperty: String? = null

    @get:Input
    @get:Optional
    var serverUrlProperty: String? = null

    @get:Input
    @get:Optional
    var targetVersionCode: Long? = null

    @get:Input
    @get:Optional
    var targetVersionName: String? = null

    @get:Input
    @get:Optional
    var releaseNotesProperty: String? = null

    @get:Input
    @get:Optional
    var minSupportedVersionCodeProperty: Long? = null

    @TaskAction
    fun generateUpdate() {
        val project = project
        val serverBaseUrl = project.findProperty("updateServerUrl")?.toString()
            ?: serverUrlProperty
            ?: System.getenv("UPDATE_SERVER_URL")
            ?: "http://192.168.1.117:4321/updates"

        val outputDirStr = project.findProperty("updateOutputDir")?.toString()
            ?: outputDirProperty
            ?: System.getenv("UPDATE_OUTPUT_DIR")
            ?: "${project.rootDir}/updates"

        val baseOutputDir = File(outputDirStr)
        val apksDir = File(baseOutputDir, "apks")
        val patchesDir = File(baseOutputDir, "patches")
        val manifestFile = File(baseOutputDir, "update-manifest.json")

        apksDir.mkdirs()
        patchesDir.mkdirs()

        val releaseDir = File(project.layout.buildDirectory.get().asFile, "outputs/apk/release")
        val releaseApkFile = File(releaseDir, "app-release.apk")
        if (!releaseApkFile.exists()) {
            throw IllegalStateException("Release APK not found at ${releaseApkFile.absolutePath}. Ensure assembleRelease ran.")
        }

        val metadata = readVersionFromMetadata(releaseDir)

        val versionCode = project.findProperty("versionCode")?.toString()?.toLongOrNull()
            ?: metadata?.first
            ?: targetVersionCode
            ?: 9L

        val versionName = project.findProperty("versionName")?.toString()
            ?: metadata?.second
            ?: targetVersionName
            ?: "0.8.0"

        var releaseNotes = project.findProperty("releaseNotes")?.toString()
            ?: releaseNotesProperty
            ?: "Release version $versionName (build $versionCode)."

        if (releaseNotes.startsWith("\"") && releaseNotes.endsWith("\"") && releaseNotes.length >= 2) {
            releaseNotes = releaseNotes.substring(1, releaseNotes.length - 1)
        }

        val minSupportedVc = project.findProperty("minSupportedVersionCode")?.toString()?.toLongOrNull()
            ?: minSupportedVersionCodeProperty
            ?: 1L

        val targetApkName = "tally-v${versionName}-vc${versionCode}.apk"
        val targetApkPath = File(apksDir, targetApkName)

        // Always copy/keep the built release APK with its correct unique version name & code
        releaseApkFile.copyTo(targetApkPath, overwrite = true)

        val fullSha256 = sha256Hex(targetApkPath)
        val fullSizeBytes = targetApkPath.length()

        val patches = mutableListOf<Map<String, Any>>()
        val apkFiles = apksDir.listFiles { _, name -> name.endsWith(".apk") } ?: emptyArray()

        for (oldApk in apkFiles) {
            if (oldApk.absolutePath == targetApkPath.absolutePath) continue

            val oldVc = extractVersionCode(oldApk.name) ?: continue
            if (oldVc >= versionCode) continue

            val patchName = "patch-v${oldVc}-to-v${versionCode}.patch"
            val patchPath = File(patchesDir, patchName)

            if (!patchPath.exists() || patchPath.length() == 0L) {
                generatePatchWithFallback(oldApk, targetApkPath, patchPath)
            }

            val patchSha256 = sha256Hex(patchPath)
            val patchSizeBytes = patchPath.length()
            val cleanServerBaseUrl = serverBaseUrl.trimEnd('/')

            patches.add(
                mapOf(
                    "fromVersionCode" to oldVc,
                    "patchUrl" to "$cleanServerBaseUrl/patches/$patchName",
                    "patchSha256" to patchSha256,
                    "patchSizeBytes" to patchSizeBytes
                )
            )
        }

        patches.sortBy { (it["fromVersionCode"] as Number).toLong() }

        val cleanBaseUrl = serverBaseUrl.trimEnd('/')
        val manifestContent = buildJsonManifest(
            latestVersionCode = versionCode,
            latestVersionName = versionName,
            minSupportedVersionCode = minSupportedVc,
            releaseNotes = releaseNotes,
            fullApkUrl = "$cleanBaseUrl/apks/$targetApkName",
            fullApkSha256 = fullSha256,
            fullApkSizeBytes = fullSizeBytes,
            patches = patches
        )

        manifestFile.writeText(manifestContent)

        logger.lifecycle("==================================================")
        logger.lifecycle("Tally Delta Update Package Generated Successfully!")
        logger.lifecycle("Target APK: ${targetApkPath.absolutePath} (${fullSizeBytes / 1024 / 1024} MB)")
        logger.lifecycle("SHA-256: $fullSha256")
        logger.lifecycle("Generated Patches: ${patches.size}")
        logger.lifecycle("Manifest: ${manifestFile.absolutePath}")
        logger.lifecycle("==================================================")
    }

    private fun readVersionFromMetadata(releaseDir: File): Pair<Long, String>? {
        val metaFile = File(releaseDir, "output-metadata.json")
        if (!metaFile.exists()) return null
        return try {
            val text = metaFile.readText()
            val vcMatch = Regex(""""versionCode"\s*:\s*(\d+)""").find(text)?.groupValues?.get(1)?.toLongOrNull()
            val vnMatch = Regex(""""versionName"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
            if (vcMatch != null && vnMatch != null) Pair(vcMatch, vnMatch) else null
        } catch (_: Exception) {
            null
        }
    }

    private fun generatePatchWithFallback(oldApk: File, newApk: File, patchOut: File) {
        logger.lifecycle("Generating BSDiff patch: ${oldApk.name} -> ${newApk.name}")
        val sysBsdiffSuccess = try {
            val process = ProcessBuilder("bsdiff", oldApk.absolutePath, newApk.absolutePath, patchOut.absolutePath)
                .redirectErrorStream(true)
                .start()
            val exitCode = process.waitFor()
            exitCode == 0 && patchOut.exists() && patchOut.length() > 0
        } catch (_: Exception) {
            false
        }

        if (!sysBsdiffSuccess) {
            logger.lifecycle("Using built-in Kotlin BsdiffGenerator engine for ${patchOut.name}...")
            BsdiffGenerator.generatePatch(oldApk, newApk, patchOut)
        }
        logger.lifecycle("Created patch ${patchOut.name} (${patchOut.length() / 1024} KB)")
    }

    private fun extractVersionCode(filename: String): Long? {
        if (!filename.contains("-vc")) return null
        return try {
            val part = filename.substringAfter("-vc").substringBefore(".apk")
            part.toLong()
        } catch (_: Exception) {
            null
        }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun buildJsonManifest(
        latestVersionCode: Long,
        latestVersionName: String,
        minSupportedVersionCode: Long,
        releaseNotes: String,
        fullApkUrl: String,
        fullApkSha256: String,
        fullApkSizeBytes: Long,
        patches: List<Map<String, Any>>
    ): String {
        val escapedNotes = escapeJsonString(releaseNotes)
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"latestVersionCode\": $latestVersionCode,\n")
        sb.append("  \"latestVersionName\": \"$latestVersionName\",\n")
        sb.append("  \"minSupportedVersionCode\": $minSupportedVersionCode,\n")
        sb.append("  \"releaseNotes\": \"$escapedNotes\",\n")
        sb.append("  \"fullApkUrl\": \"$fullApkUrl\",\n")
        sb.append("  \"fullApkSha256\": \"$fullApkSha256\",\n")
        sb.append("  \"fullApkSizeBytes\": $fullApkSizeBytes,\n")
        sb.append("  \"patches\": [\n")
        patches.forEachIndexed { index, patch ->
            val fromVc = patch["fromVersionCode"]
            val url = patch["patchUrl"]
            val sha = patch["patchSha256"]
            val size = patch["patchSizeBytes"]
            sb.append("    {\n")
            sb.append("      \"fromVersionCode\": $fromVc,\n")
            sb.append("      \"patchUrl\": \"$url\",\n")
            sb.append("      \"patchSha256\": \"$sha\",\n")
            sb.append("      \"patchSizeBytes\": $size\n")
            sb.append("    }")
            if (index < patches.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("  ]\n")
        sb.append("}\n")
        return sb.toString()
    }

    private fun escapeJsonString(str: String): String {
        return str.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\u000C", "\\f")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
