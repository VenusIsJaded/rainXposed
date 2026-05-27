package io.github.revenge.xposed

class Constants {
    companion object {
        const val TARGET_PACKAGE = "com.discord"
        const val TARGET_ACTIVITY = "$TARGET_PACKAGE.react_activities.ReactActivity"

        const val FILES_DIR = "files/rain"
        const val CACHE_DIR = "cache/rain"
        // Store the fetched payload as JS. Even when the source URL is a .hbc
        // fallback, the default path should not imply Hermes bytecode: several
        // Discord/Hermes versions crash with "Buffer misaligned" when loading
        // external bytecode files.
        const val MAIN_SCRIPT_FILE = "bundle.js"


        const val LOG_TAG = "Rain"

        const val LOADER_NAME = "RainXposed"

        const val USER_AGENT = "RainXposed"
    }
}