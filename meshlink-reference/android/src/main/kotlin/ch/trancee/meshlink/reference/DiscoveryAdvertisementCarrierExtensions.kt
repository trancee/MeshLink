package ch.trancee.meshlink.reference

import ch.trancee.meshlink.platform.android.DiscoveryAdvertisementCarrier

internal fun String?.toDiscoveryAdvertisementCarrier(): DiscoveryAdvertisementCarrier {
    return when {
        this.equals(
            DiscoveryAdvertisementCarrier.UUID_PAIR_PLUS_SERVICE_DATA.name,
            ignoreCase = true,
        ) -> DiscoveryAdvertisementCarrier.UUID_PAIR_PLUS_SERVICE_DATA
        else -> DiscoveryAdvertisementCarrier.UUID_PAIR
    }
}
