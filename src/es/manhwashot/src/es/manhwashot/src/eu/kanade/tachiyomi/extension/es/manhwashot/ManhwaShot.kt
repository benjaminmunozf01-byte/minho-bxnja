package eu.kanade.tachiyomi.extension.es.manhwashot

import eu.kanade.tachiyomi.multisrc.madara.Madara
import keiyoushi.annotation.Source

@Source
abstract class ManhwaShot : Madara("ManhwaShot", "https://manhwashot.lat", "es") {
    override val mangaUrlDirectory = "comic"
}
