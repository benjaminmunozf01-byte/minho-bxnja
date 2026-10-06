import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "ManhwaShot"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
}

dependencies {
    implementation(project(":lib-multisrc:mangathemesia"))
}
