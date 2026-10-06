import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "ManhwaShot"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    
    source {
        lang = "es"
        baseUrl = "https://manhwashot.lat"
    }
}

dependencies {
    implementation(project(":lib-multisrc:mangathemesia"))
}
