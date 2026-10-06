import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "ManhwaShot"
    versionCode = 1
    contentWarning = ContentWarning.NSFW

    multisrc {
        libVersion = "1.4"
        theme = "mangathemesia"
    }

    source {
        lang = "es"
        baseUrl = "https://manhwashot.lat"
    }
}

dependencies {
    implementation(project(":lib-multisrc:mangathemesia"))
}
