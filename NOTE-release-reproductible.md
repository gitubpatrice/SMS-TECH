# Note — un APK de release doit être construit `--no-build-cache`

> Écrit le **2026-08-26**, le jour où la vérification F-Droid de la **v1.27.9 (285)** a échoué.
> Il dit la règle, ce qui a été mesuré pour l'établir, et l'ordre des opérations à respecter.

## La règle, en une ligne

**Tout APK destiné à `Binaries:` se construit à froid :**

```bash
./gradlew --no-build-cache clean :app:assembleRelease
```

`clean` seul **ne suffit pas**. [gradle.properties:16](gradle.properties#L16) porte
`org.gradle.caching=true` : après un `clean`, Gradle réhydrate les sorties de tâches depuis le
cache local, et l'APK produit peut ne pas être celui qu'une machine vierge produirait.

## Ce qui s'est passé le 2026-08-26

La v1.27.9 a été publiée avec un APK construit sur une machine au cache chaud. Le job
`fdroid build` de la pipeline `2793826511` a reconstruit l'app depuis `51ecddb`, recopié la
signature, et comparé au binaire publié :

```
Binary files …/assets/dexopt/baseline.prof … differ
Binary files …/classes.dex … differ
Binary files …/classes2.dex … differ
```

Trois fichiers, et eux seuls — ni ressources, ni manifeste, ni bibliothèques natives. Le profil de
démarrage pilote la répartition des classes entre `classes.dex` et `classes2.dex` ; une différence
de dex entraîne mécaniquement une différence de `baseline.prof`.

## La mesure qui tranche

L'artefact du job en échec a été téléchargé (`tmp/com.filestech.sms_285.apk`) et comparé aux deux
builds locaux, plutôt que de republier au jugé :

| `sha256` (16 premiers caractères) | `classes.dex` | `classes2.dex` | `baseline.prof` |
|---|---|---|---|
| **F-Droid**, à froid | `33430cfde98f16d9` | `140d9326878d4c34` | `6da9e821318009bd` |
| Local, **`clean` avec cache** (= l'APK publié à tort) | `f693a898bbf7ee49` | `4dbd7efb519a7af1` | `5044e59afd5189d1` |
| Local, **`--no-build-cache clean`** | `33430cfde98f16d9` | `140d9326878d4c34` | `6da9e821318009bd` |

Le build sans cache correspond **exactement** à celui de leur serveur. Le cache est donc la seule
variable en cause : ni le JDK (local **17.0.18**, le leur **21**, et la 1.27.8 reproduisait
malgré cet écart), ni les profils commités
(`app/src/release/generated/baselineProfiles/`, inchangés depuis le 2026-07-23, arbre git propre),
ni le correctif lui-même.

Correctif appliqué : les 4 assets de la release `v1.27.9` remplacés par ceux du build sans cache
(`gh release upload --clobber`), tag et commit inchangés, donc recette F-Droid toujours valide.
Vérifié en **retéléchargeant depuis GitHub** : `classes.dex` publié = `33430cfde98f16d9`.

## L'ordre des opérations, qui n'a pas été respecté

L'erreur de méthode a coûté plus cher que l'erreur de build : la recette F-Droid a été bumpée et
le commentaire de MR posté **avant** que la pipeline ait validé quoi que ce soit. La MR a donc
affiché une pipeline rouge sous un commentaire annonçant que tout allait bien.

L'ordre correct :

1. `--no-build-cache clean :app:assembleRelease`
2. contrôler `versionCode` / `versionName` (`aapt2 dump badging`) et le certificat
   (`apksigner verify --print-certs` → doit rendre `b09a9511…c687d`, la valeur d'`AllowedAPKSigningKeys`)
3. tag + release GitHub avec les **4** assets : les 3 splits d'ABI **et**
   `sms-tech-universel-<version>.apk` — c'est ce dernier que pointe `Binaries:`, sans lui F-Droid
   n'a rien à comparer
4. **attendre que `fdroid build` soit vert**
5. seulement ensuite : bumper la recette sur `add-sms-tech`, puis commenter la MR

## Deux détails qui reviennent à chaque fois

**`fdroid rewritemeta` local ≠ celui de leur CI.** Le nôtre veut replier `Binaries:` sur une seule
ligne ; leur job `fdroid rewritemeta` accepte la forme sur deux lignes et passe au vert. Ne pas
reformater ce champ : le sujet a déjà fait un aller-retour avec un mainteneur sur Agenda Tech
(`ff34e226`, « put back the space after Binaries:, rewritemeta wants it »).

**« Une seule sortie » concerne la recette, pas la release.** Le commit `7c1fa3f8`
(« single build entry, build from source ») a réduit la recette à **une** entrée de build produisant
`app-universal-release-unsigned.apk`. La release GitHub, elle, porte toujours **4** fichiers —
identique à Agenda Tech (`7713e1dc`, même changement, et sa v1.0.3 publie bien 4 assets).

## Ce qui reste à faire

- [ ] Inscrire `--no-build-cache` dans la procédure de release (agent `android-release-orchestrator`
      et checklist `files-tech-release-checklist.md`), pour les **9** apps : le piège est dans
      `gradle.properties`, il n'a rien de spécifique à SMS Tech.
- [x] **Vérifié le 2026-08-26** — les trois apps en cours de revue F-Droid sont Agenda Tech
      (`!42991`), SMS Tech (`!38458`) et Notes Tech (`!37885`). Sur les deux autres :

      | App | `org.gradle.caching` | Recette avec `Binaries:` | Exposée |
      |---|---|---|---|
      | **Agenda Tech** | **`=true`** | **oui** | **OUI — mêmes deux conditions** |
      | Notes Tech | absent (donc désactivé) | non | non |

      **Agenda Tech réunit les deux conditions.** Sa prochaine release construite sur une machine
      au cache chaud échouera de la même façon. Notes Tech ne risque rien : cache désactivé, et sa
      recette ne compare aucun binaire — F-Droid construit et signe lui-même.
