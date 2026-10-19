package com.moosa.hub

/**
 * A CloudStream home RecyclerView is shared by ALL selected providers.
 * A title left behind by a recycled row does not mean MOOSA is active.
 */
internal object MOOSAShowMorePolicy {
    const val OWNER_API_NAME = "MOOSA Hub"

    fun shouldShow(selectedProviderName: String?, knownMoosaCategory: Boolean): Boolean =
        knownMoosaCategory && selectedProviderName?.trim() == OWNER_API_NAME
}
