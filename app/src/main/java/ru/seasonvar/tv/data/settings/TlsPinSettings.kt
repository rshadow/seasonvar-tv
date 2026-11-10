package ru.seasonvar.tv.data.settings

import okhttp3.CertificatePinner

internal object TlsPinSettings {
    const val ISRG_ROOT_YR = "sha256/fk6IOKit1ild5647BH06ujSIq5XbCgqlbYl6ANhhi88="
    const val ISRG_ROOT_YE = "sha256/sCkq5UWXjg+7mKu9lMhhYF5bGLsy7VI/UNW3tccdR7w="
    const val SECTIGO_ROOT_E46 = "sha256/sLVjNUaFYfW7n6EtgBeEpjOlcnBdNPMrZDRF36iwBdE="
    const val SECTIGO_ROOT_R46 = "sha256/Douxi77vs4G+Ib/BogbTFymEYq0QSFXwSgVCaZcI09Q="
    const val DIGICERT_GLOBAL_ROOT_G3 = "sha256/uUwZgwDOxcBXrQcntwu+kYFpkiVkOaezL0WYEZ3anJc="
    const val DIGICERT_GLOBAL_ROOT_G2 = "sha256/i7WTqTvh0OioIruIfFR4kMPnBqrS2rdiVPl/s2uC/CY="

    val certificatePinner: CertificatePinner = CertificatePinner.Builder()
        .add("seasonvar.ru", ISRG_ROOT_YR, ISRG_ROOT_YE)
        .add("*.seasonvar.ru", ISRG_ROOT_YR, ISRG_ROOT_YE)
        .add("svstat.net", ISRG_ROOT_YR, ISRG_ROOT_YE)
        .add("*.svstat.net", ISRG_ROOT_YR, ISRG_ROOT_YE)
        .add("github.com", SECTIGO_ROOT_E46, SECTIGO_ROOT_R46)
        .add("*.github.com", SECTIGO_ROOT_E46, SECTIGO_ROOT_R46)
        .add("githubusercontent.com", ISRG_ROOT_YR, ISRG_ROOT_YE)
        .add("*.githubusercontent.com", ISRG_ROOT_YR, ISRG_ROOT_YE)
        .add("dns.quad9.net", DIGICERT_GLOBAL_ROOT_G3, DIGICERT_GLOBAL_ROOT_G2)
        .build()
}
