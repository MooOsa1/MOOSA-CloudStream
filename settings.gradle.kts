rootProject.name = "MOOSACloudStreamPlugins"
include("MOOSAHub")
include("MOOSAArabic")

// Disposable compatibility probe is excluded from official plugin builds.
// Explicit opt-in from the isolated emulator CI workflow only.
if (System.getenv("MOOSA_ENABLE_PROBE") == "1") include("MOOSAProbe")
