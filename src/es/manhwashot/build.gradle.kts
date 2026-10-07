import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "ManhwaShot"
    versionCode = 10
    contentWarning = ContentWarning.MIXED
    libVersion = "1.4"

    source {
        lang = "es"
        baseUrl = "https://manhwashot.lat"
    }
}
