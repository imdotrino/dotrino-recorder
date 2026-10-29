pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "recorder"
include(":app")
// La librería nativa del ecosistema (identidad, almacén, barra): el repo dotrino-native, como
// submódulo en ../native. CONVENCIONES §16.2: nada de eso se reimplementa en la app.
includeBuild("../native/android")
