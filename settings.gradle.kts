pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
// Pas de `plugins { foojay-resolver-convention }` ici, volontairement.
//
// Le projet ne demande aucune toolchain JVM : il compile en Java 17 via
// `sourceCompatibility` / `targetCompatibility` / `jvmTarget`. Le résolveur n'avait donc
// rien à résoudre.
//
// Deux raisons de l'avoir retiré (2026-08-14) :
//  1. le scanner F-Droid le refuse — il télécharge un JDK depuis le réseau pendant le
//     build, ce qui n'est pas reproductible ;
//  2. surtout, sa présence CHANGE LE BINAIRE PRODUIT. Mesuré sur Agenda Tech : deux builds
//     propres du même commit, l'un avec ce bloc et l'autre sans, donnent des `classes.dex`
//     différents — R8 renomme autrement et compte 7 champs de plus, et le profil ART qui en
//     dérive suit. Tant que la recette F-Droid le retirait par `sed` alors que la release
//     était construite avec, les deux binaires ne pouvaient PAS coïncider, ce qui interdisait
//     `Binaries:` / `AllowedAPKSigningKeys:` et donc la distribution sous notre signature.
//
// Le retirer ici plutôt que dans la recette fait construire à F-Droid exactement ce qui est
// publié. Ne pas le réintroduire.
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "SMS Tech"
include(":app")
include(":core")
include(":domain")
include(":data")
include(":baselineprofile")
