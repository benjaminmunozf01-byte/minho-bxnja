plugins {
    alias(kei.plugins.multisrc)
}

multisrc {
    theme = "mangathemesia"
}

source {
    name = "ManhwaShot"
    baseUrl = "https://manhwashot.lat"
    lang = "es"
    isNsfw = true
}
