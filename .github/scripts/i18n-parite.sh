#!/usr/bin/env bash
#
# Controle de parite des traductions. UNE commande a retenir, citee telle quelle dans
# TRANSLATING.md et dans la CI :
#
#   ./.github/scripts/i18n-parite.sh
#
# Il fait DEUX choses, dans cet ordre, et l'ordre est le propos :
#   1. le controle NEGATIF, qui prouve que l'instrument sait rougir (13 defauts reproduits
#      dans un arbre jetable, plus un temoin positif) ;
#   2. le controle de parite lui-meme sur le depot.
#
# Mesurer avec un instrument dont on n'a pas verifie qu'il devie, c'est ce qui a deja donne a
# ce projet des tests verts sur le defaut qu'ils visaient. Les deux ensemble coutent moins
# d'une seconde. Rendu 0 si tout est aligne, 1 sinon - aucun mode avertissement.
#
# La logique est en Python parce qu'un strings.xml ne se lit pas au grep : les parametres de
# format et les pluriels demandent un vrai parcours d'arbre.

set -euo pipefail

ICI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if command -v python3 > /dev/null 2>&1; then
    PY=python3
elif command -v python > /dev/null 2>&1; then
    PY=python
else
    echo "ERREUR : ni python3 ni python sur ce poste." >&2
    exit 2
fi

echo "--- l'instrument sait-il rougir ? ---"
"$PY" "$ICI/i18n-parite-controle-negatif.py"

echo
echo "--- parite des traductions du depot ---"
exec "$PY" "$ICI/i18n-parite.py"
