import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "ManhwaShot"
    versionCode = 9
    contentWarning = ContentWarning.MIXED

    source {
        lang = "es"
        baseUrl = "https://manhwashot.lat"
    }
}
