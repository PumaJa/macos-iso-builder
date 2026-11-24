pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenLocal(); google(); mavenCentral() }
}
rootProject.name = "KineticOS"
include(
    ":core:vesc-protocol",   // VESC packet codec, commands, client, CAN, simulator
    ":core:telemetry",       // derived metrics, ride statistics, data-quality tagging
    ":core:vehicle",         // garage models, ride profiles, motor characteristics, safety validation
    ":core:racing",          // lap timing, sectors, ghost
    ":core:export",          // CSV / JSON / GPX import+export
    ":app",                  // Android app: UI, BLE/USB transport, Room, services, maps, multiplayer, telematics
)
