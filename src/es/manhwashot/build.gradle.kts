import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "ManhwaShot"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"
    theme = "madara"

    source {
        lang = "es"
        baseUrl = "https://manhwashot.lat"
    }
}

dependencies {
    implementation(project(":lib-multisrc:madara"))
}
