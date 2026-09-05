plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))
}

tasks.test {
    useJUnit()
    // The golden-file test walks 5.4 MB of flash; give it room and show results.
    maxHeapSize = "1g"
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
