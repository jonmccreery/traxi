pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // usb-serial-for-android is published here and nowhere else.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "btdroid"

// Pure-JVM parser/protocol core. No Android dependencies, so the golden-file
// and round-trip tests run on the desktop without a device or emulator.
include(":core")

// Android app shell: Bluetooth transport, foreground service, Compose UI.
include(":app")
