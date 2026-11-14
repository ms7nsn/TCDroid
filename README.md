# TCDroid

Application Android pour ouvrir et créer des volumes **compatibles TrueCrypt 7.x** (conteneurs de fichiers, systèmes de fichiers FAT12/16/32).
Réécriture indépendante en Java, d'après le format décrit par le code source de TrueCrypt 7.1a.

## Compiler avec GitHub
1. Créez un dépôt GitHub et poussez tout ce dossier (`git init`, `git add .`, `git commit`, `git push`).
2. Onglet **Actions** → workflow « Build APK » (il se lance à chaque push).
3. Une fois terminé, téléchargez l'artefact **TCDroid-debug-apk** → `app-debug.apk`, puis installez-le sur le téléphone
   (autoriser les sources inconnues). Le workflow `tests-coeur` exécute aussi les tests du cœur crypto + FAT.

Compilation locale : Android Studio (ouvrir le dossier) ou `gradle assembleDebug` (Gradle 8.9, JDK 17, SDK Android 34).

## Interface
- Material 3 / **Material You** : couleurs dynamiques tirées du fond d'écran (Android 12+), palette Material 3 par défaut avant.
- Icône d'application : une clé (icône adaptative, avec variante monochrome pour les icônes thématiques d'Android 13+).
- **Paramètres** (engrenage en haut de l'écran principal) : thème clair / sombre / système, langue système / English / Français.
  Tous les textes, y compris les messages d'erreur, existent en français et en anglais.

## Utilisation
- **Créer un volume** : taille, algorithme (AES, Serpent, Twofish, cascades), hachage, mot de passe, fichiers de clés,
  volume caché facultatif. Le volume est formaté en FAT.
- **Déverrouiller** : choisir le conteneur, saisir mot de passe (+ fichiers de clés). Tout volume normal ou caché est détecté
  d'après le mot de passe.
- **Accès aux fichiers** : une fois déverrouillé, une entrée « TCDroid » apparaît dans l'application **Fichiers** d'Android
  (copier, ouvrir, créer, renommer, supprimer). Verrouillage manuel ou après 15 min d'inactivité.

## Prise en charge / limites
- Pris en charge : volumes TrueCrypt 6.x–7.x (XTS), normaux et cachés, en-têtes de secours, fichiers de clés, AES/Serpent/Twofish
  et cascades, RIPEMD-160/SHA-512/Whirlpool/SHA-1, FAT12/16/32 avec noms longs.
- Non pris en charge : volumes < 6.0, chiffrement de partition/disque système, jetons PKCS#11, exFAT/NTFS à l'intérieur du volume.
- Android ne permet pas de monter un volume comme un disque sans root : l'accès se fait par le fournisseur de documents (SAF).
- Mots de passe : 64 octets maximum (UTF-8).

## Tests
`bash tests/run-tests.sh` (JDK 17, Python 3 + `cryptography`, accès à Maven Central). Les primitives ont été comparées aux
sources C originales ; le format est vérifié contre un générateur Python indépendant.

## Avertissements
- Code non audité : n'y confiez pas de données uniques sans sauvegarde, et ne le présentez pas comme remplaçant un logiciel audité (VeraCrypt).
- Licence : le code est écrit à partir du format, sans copie du code de TrueCrypt ; la licence « TrueCrypt License 3.0 » du code source d'origine
  est toutefois restrictive pour les œuvres dérivées. Vérifiez-la avant toute publication, et choisissez la licence de votre dépôt.
