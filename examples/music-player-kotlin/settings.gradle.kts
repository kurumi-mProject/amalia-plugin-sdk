rootProject.name = "music-player-plugin"

// contracts — стабы контрактов хоста (compileOnly, в dex НЕ попадают)
// plugin    — исходники плагина → plugin.jar → classes.dex
include(":contracts", ":plugin")
