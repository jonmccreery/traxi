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
    }
}

rootProject.name = "btdroid"

// Pure-JVM parser/protocol core. No Android dependencies, so the golden-file
// test runs on the JVM without a device or emulator.
include(":core")

// Android app shell: transports, foreground service, Compose UI.
// Enabled once phase 1 (parser + golden test) is green.
// include(":app")
