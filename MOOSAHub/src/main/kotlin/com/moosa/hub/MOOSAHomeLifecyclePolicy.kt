package com.moosa.hub

/** CloudStream can recreate HomeFragment without re-requesting the catalog. */
internal object MOOSAHomeLifecyclePolicy {
    fun isHomeFragment(className: String): Boolean =
        className == "com.lagradost.cloudstream3.ui.home.HomeFragment" ||
            className.endsWith(".home.HomeFragment")
}
