package io.github.revenge.xposed.modules

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.util.AtomicFile
import android.widget.Toast
import de.robv.android.xposed.callbacks.XC_LoadPackage
import dev.rushii.libunbound.LibUnbound
import io.github.revenge.xposed.Constants
import io.github.revenge.xposed.Module
import io.github.revenge.xposed.Utils
import io.github.revenge.xposed.Utils.Companion.JSON
import io.github.revenge.xposed.Utils.Companion.reloadApp
import io.github.revenge.xposed.Utils.Log
import io.github.revenge.xposed.modules.bridge.BridgeModule
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.lang.ref.WeakReference

@Serializable
data class CustomLoadUrl(val enabled: Boolean = false, val url: String = "")

@Serializable
data class LoaderConfig(
    val customLoadUrl: CustomLoadUrl = CustomLoadUrl(),
    val disableInjection: Boolean = false,
    val usePrereleases: Boolean = false,
)

@Serializable
data class GitHubReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
)

@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    val assets: List<GitHubReleaseAsset> = emptyList(),
)

private data class ParsedVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val prerelease: List<String>,
) : Comparable<ParsedVersion> {
    override fun compareTo(other: ParsedVersion): Int {
        var cmp = major.compareTo(other.major)
        if (cmp != 0) return cmp

        cmp = minor.compareTo(other.minor)
        if (cmp != 0) return cmp

        cmp = patch.compareTo(other.patch)
        if (cmp != 0) return cmp

        if (prerelease.isEmpty() && other.prerelease.isEmpty()) return 0
        if (prerelease.isEmpty()) return 1
        if (other.prerelease.isEmpty()) return -1

        for (index in 0 until maxOf(prerelease.size, other.prerelease.size)) {
            val left = prerelease.getOrNull(index) ?: return -1
            val right = other.prerelease.getOrNull(index) ?: return 1

            cmp = comparePrereleaseIdentifier(left, right)
            if (cmp != 0) return cmp
        }

        return 0
    }
}

private fun comparePrereleaseIdentifier(left: String, right: String): Int {
    val leftNumber = left.toIntOrNull()
    val rightNumber = right.toIntOrNull()

    return when {
        leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
        leftNumber != null -> -1
        rightNumber != null -> 1
        else -> left.compareTo(right)
    }
}

private fun parseVersion(version: String): ParsedVersion? {
    val sanitized = version.removePrefix("v").substringBefore('+')
    val coreAndPrerelease = sanitized.split("-", limit = 2)
    val coreParts = coreAndPrerelease[0].split(".")

    if (coreParts.size < 3) return null

    val major = coreParts[0].toIntOrNull() ?: return null
    val minor = coreParts[1].toIntOrNull() ?: return null
    val patch = coreParts[2].toIntOrNull() ?: return null
    val prerelease = coreAndPrerelease.getOrNull(1)
        ?.split(Regex("[.-]"))
        ?.filter { it.isNotBlank() }
        .orEmpty()

    return ParsedVersion(major, minor, patch, prerelease)
}

private fun compareReleaseVersions(left: GitHubRelease, right: GitHubRelease): Int {
    val leftVersion = parseVersion(left.tagName)
    val rightVersion = parseVersion(right.tagName)

    return when {
        leftVersion != null && rightVersion != null -> leftVersion.compareTo(rightVersion)
        else -> (left.publishedAt ?: "").compareTo(right.publishedAt ?: "")
    }
}

object UpdaterModule : Module() {
    private lateinit var config: LoaderConfig
    val isCustomUrlEnabled: Boolean
        get() = config.customLoadUrl.enabled
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastActivity: WeakReference<Activity>? = null

    private lateinit var cacheDir: File
    private lateinit var bundle: File
    private lateinit var etag: File
    private lateinit var configFile: File

    private const val TIMEOUT_STRICT = 15000L
    private const val MIN_BYTECODE_SIZE = 512
    private const val ETAG_FILE = "etag.txt"
    private const val CONFIG_FILE = "loader.json"
    private const val RELEASES_API_URL = "https://api.github.com/repos/VenusIsJaded/rain/releases"
    private const val DEFAULT_BASE_URL = "https://github.com/VenusIsJaded/rain/releases/latest/download/"
    private const val DEFAULT_BUNDLE_NAME = "rain.js"

    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) = with(packageParam) {
        cacheDir = File(appInfo.dataDir, Constants.CACHE_DIR).apply { mkdirs() }
        val filesDir = File(appInfo.dataDir, Constants.FILES_DIR).apply { mkdirs() }

        bundle = File(cacheDir, Constants.MAIN_SCRIPT_FILE)
        etag = File(cacheDir, ETAG_FILE)
        configFile = File(filesDir, CONFIG_FILE)
        config = runCatching {
            if (configFile.exists()) JSON.decodeFromString<LoaderConfig>(configFile.readText()) else LoaderConfig()
        }.getOrDefault(LoaderConfig())

        BridgeModule.registerMethod("updater.clear") {
            if (bundle.exists()) bundle.delete()
            if (etag.exists()) etag.delete()
            null
        }

        BridgeModule.registerMethod("updater.download") {
            scope.launch { downloadScript(showUpdateDialog = false, isExplicit = true).join() }
            null
        }

        BridgeModule.registerMethod("updater.reload") {
            scope.launch {
                downloadScript(showUpdateDialog = false, isExplicit = true).join()
                withContext(Dispatchers.Main) { reloadApp() }
            }
            null
        }

        BridgeModule.registerMethod("updater.setUsePrereleases") { args ->
            val enabled = (args.getOrNull(0) as? Boolean) ?: false
            persistConfig(readPersistedConfig().copy(usePrereleases = enabled))
            null
        }
    }

    fun downloadScript(activity: Activity? = null, showUpdateDialog: Boolean = true, isExplicit: Boolean = false): Job = scope.launch {
        try {
            HttpClient(CIO) {
                expectSuccess = false
                install(HttpTimeout) {
                    requestTimeoutMillis = TIMEOUT_STRICT
                    connectTimeoutMillis = 5000L
                    socketTimeoutMillis = TIMEOUT_STRICT
                }
                install(UserAgent) { agent = Constants.USER_AGENT }
                install(HttpRedirect) { checkHttpMethod = false }
            }.use { client ->
                val targetUrl = resolveTargetUrl(client)
                //Log.i("Fetching bundle: $targetUrl")

                val response: HttpResponse = client.get(targetUrl) {
                    headers {
                        if (etag.exists() && bundle.exists()) {
                            append(HttpHeaders.IfNoneMatch, etag.readText())
                        }
                    }
                }

                when (response.status) {
                    HttpStatusCode.OK -> {
                        val bytes: ByteArray = response.body()

                        if (bytes.size < MIN_BYTECODE_SIZE) {
                            throw Exception("Payload too small (${bytes.size} bytes). Possible corrupt build.")
                        }

                        AtomicFile(bundle).apply {
                            val stream = startWrite()
                            try {
                                stream.write(bytes)
                                finishWrite(stream)
                            } catch (e: Exception) {
                                failWrite(stream)
                                throw e
                            }
                        }

                        response.headers[HttpHeaders.ETag]?.let { etag.writeText(it) } ?: etag.delete()
                        //Log.i("Bundle updated: ${bytes.size} bytes")

                        if (showUpdateDialog && activity != null) {
                            withContext(Dispatchers.Main) {
                                AlertDialog.Builder(activity)
                                    .setTitle("Update Successful")
                                    .setMessage("Reload required.")
                                    .setPositiveButton("Reload") { _, _ -> reloadApp() }
                                    .setCancelable(false)
                                    .show()
                            }
                        }
                    }

                    HttpStatusCode.NotModified -> //Log.i("Bundle is up to date (304)")
                    else -> throw ResponseException(response, "HTTP ${response.status}")
                }
            }
        } catch (e: Throwable) {
            Log.e("Updater Error", e)
            if (activity != null) withContext(Dispatchers.Main) {
                Toast.makeText(activity, "Update failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun resolveTargetUrl(client: HttpClient): String {
        if (config.customLoadUrl.enabled && config.customLoadUrl.url.isNotEmpty()) {
            return config.customLoadUrl.url
        }

        return try {
            val release = resolveTargetRelease(client)
            val assets = release?.assets.orEmpty().associateBy { it.name }
            val hermesVersion = withTimeoutOrNull(2000L) {
                runCatching { LibUnbound.getHermesRuntimeBytecodeVersion() }.getOrNull()
            } ?: 96

            assets["rain.$hermesVersion.hbc"]?.browserDownloadUrl
                ?: assets["rain.min.js"]?.browserDownloadUrl
                ?: assets[DEFAULT_BUNDLE_NAME]?.browserDownloadUrl
                ?: DEFAULT_BASE_URL + DEFAULT_BUNDLE_NAME
        } catch (e: Exception) {
            DEFAULT_BASE_URL + DEFAULT_BUNDLE_NAME
        }
    }

    private suspend fun resolveTargetRelease(client: HttpClient): GitHubRelease? {
        val response = client.get(RELEASES_API_URL)
        if (response.status != HttpStatusCode.OK) return null

        val releases = JSON.decodeFromString<List<GitHubRelease>>(response.bodyAsText())
        val stableReleases = releases.filter { !it.draft && !it.prerelease }
        val prereleaseReleases = releases.filter { !it.draft && it.prerelease }
        val candidates = if (config.usePrereleases && prereleaseReleases.isNotEmpty()) {
            prereleaseReleases
        } else {
            stableReleases
        }

        return candidates.maxWithOrNull { left, right -> compareReleaseVersions(left, right) }
    }

    override fun onActivity(activity: Activity) {
        lastActivity = WeakReference(activity)
    }

    private fun persistConfig(newConfig: LoaderConfig) {
        config = newConfig
        if (::configFile.isInitialized) {
            configFile.parentFile?.mkdirs()
            configFile.writeText(JSON.encodeToString(newConfig))
        }
    }

    private fun readPersistedConfig(): LoaderConfig {
        return runCatching {
            if (::configFile.isInitialized && configFile.exists()) {
                JSON.decodeFromString<LoaderConfig>(configFile.readText())
            } else {
                config
            }
        }.getOrElse {
            if (::config.isInitialized) config else LoaderConfig()
        }
    }

    fun setDisableInjection(context: Context, disabled: Boolean) {
        persistConfig(readPersistedConfig().copy(disableInjection = disabled))
        Toast.makeText(context, "Injection ${if (disabled) "disabled" else "enabled"}", Toast.LENGTH_SHORT).show()
    }

    fun isInjectionDisabled(context: Context? = null): Boolean {
        return if (::config.isInitialized) config.disableInjection else false
    }

    fun updateConfig(newConfig: LoaderConfig) {
        persistConfig(newConfig)
    }
}
