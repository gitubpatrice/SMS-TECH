Voici le résultat de la relecture. Un seul défaut réel a été identifié, mais il est majeur et touche directement la promesse d'intégrité du journal. Les autres motifs demandés (D1, D5, D7, D8) ont été vérifiés et sont correctement protégés par la conception actuelle.

### 1. Fichier et ligne
`domain/src/main/java/com/filestech/sms/domain/safetycall/journal/SafetyCallJournalRedactor.kt`, ligne 62 :
```kotlin
val identity = address.blockKey()
```

### 2. Le scénario d'échec concret (Motifs D6 et D4)
Le journal promet que **« même proche ⇒ même jeton »** pour prouver qu'aucun contact n'a reçu deux fois la même relance. Or, `blockKey()` n'est pas *région-aware* et se contente de garder les 9 derniers chiffres (comme documenté explicitement dans `StringExt.kt`). Cela produit deux échecs opposés à l'international :

*   **Faux positif (collision)** : Si l'utilisateur a un contact américain `+1 561 234 5678` et un contact français `01 23 45 67 89`, `blockKey()` ampute l'indicatif US et produit `612345678` pour les deux. Ils obtiendront le **même jeton**. Le journal affirmera à tort qu'il s'agit de la même personne.
*   **Faux négatif (scission)** : Si l'utilisateur est à Singapour et a le même contact enregistré sous sa forme internationale `+65 6521 8000` et composé sous sa forme nationale `65218000`, `blockKey()` produira `565218000` pour le premier et `65218000` pour le second. Ils obtiendront des **jetons différents**. Le journal échouera à prouver qu'il s'agit du même destinataire.

**Lien avec le motif D4 (Test vert sur un chemin mort)** : Le test `le meme proche donne le meme jeton quelle que soit la forme du numero` dans `SafetyCallJournalRedactorTest` est vert uniquement parce qu'il utilise des numéros français (`0607231541` et `+33607231541`), masquant ainsi le défaut de la fonction sous-jacente pour le reste du monde.

### 3. Gravité
**Majeur**. Cela falsifie la preuve d'identité du journal (qui est sa raison d'être) dès que l'utilisateur ou ses contacts sortent du plan de numérotation français à 9 chiffres significatifs.

### 4. Correctif minimal proposé
Remplacer `blockKey()` par `phoneIdentityKey()`, qui a été introduit dans `StringExt.kt` précisément pour corriger ce défaut d'identité. Cela nécessite d'injecter le résolveur E.164 dans le rédacteur :

```kotlin
// Dans SafetyCallJournalRedactor.kt
fun redact(address: String, salt: String, toE164: (String) -> String?): String {
    val identity = phoneIdentityKey(address, toE164)
    if (identity.isBlank() || salt.length < SALT_MIN_LENGTH) return OPAQUE_TOKEN
    // ...
}
```
*(Il faudra également mettre à jour les tests pour fournir un mock `toE164` et ajouter un cas de test avec des numéros internationaux hors France pour verrouiller la correction).*

### 5. Statut
`CONFIRMÉ`. Démontrable par la lecture croisée de `SafetyCallJournalRedactor.kt` et de la documentation de `phoneIdentityKey` dans `StringExt.kt`.

---

*Note sur les autres motifs :*
*   **D1 (Repli)** : Le repli sans sel rend bien `OPAQUE_TOKEN` sans fuite.
*   **D5 (Décalage/Fabrication)** : L'assainissement par `Regex("[\\u0000-\\u001F\\u007F|]")` couvre bien les sauts de ligne (`\n`, `\r`) et le séparateur. Le caractère `‥` survit correctement.
*   **D8 (Bornage)** : L'écart entre `BYTE_TRIGGER` (32 Ko) et `MAX_BYTES` (12 Ko) garantit mathématiquement la convergence de l'élagage. Le garde-fou en octets est robuste.