<p align="center"><img src="docs/logo.png" width="120" alt="Caloo"></p>

# Caloo

Ton DNS perso, tes règles, tes sites, pour tous tes appareils, **sans root**.

```
 Tes appareils (téléphone, PC…) ──WireGuard──▶ VPS
                                                ├─ DNS Caloo  (10.8.0.1:53)   tes règles
                                                ├─ Web Caloo  (10.8.0.1:80)   tes sites / proxy
                                                └─ API Caloo  (10.8.0.1:8053) pilotée par l'app
```

- **Tunnel partiel (« split tunnel »)** : seuls le DNS et tes sites privés passent par le VPS. Le reste d'internet passe en direct par ta connexion habituelle.
- Rien n'est exposé sur internet, à part le port WireGuard (UDP 51820).
- L'app Android est un tableau de bord léger (moins de 1 Mo, sans aucune bibliothèque externe).

## 1. Installer le serveur (Ubuntu, VM ou conteneur LXC)

```bash
git clone https://github.com/jules-mahounou/caloo.git
cd caloo
sudo ./install.sh
```

Le script s'occupe de tout :
- WireGuard : version noyau, ou bascule automatique sur `wireguard-go` en conteneur LXC ;
- téléchargement du serveur déjà compilé par GitHub (`CALOO_BUILD=1` pour compiler sur place) et service systemd `caloo` ;
- Caloo écoute sur les ports 10053 et 10080. Les ports 53 et 80 **du tunnel uniquement** y sont redirigés, donc aucun conflit avec un nginx, Apache, Docker ou DNS déjà présent ;
- nettoyage des fichiers de compilation inutiles ;
- extensions d'exemple ;
- création de l'appareil « telephone », avec un **QR code** à scanner.

À la fin, il affiche le **token** à mettre dans l'app.

> Conteneur LXC : si le script dit que `/dev/net/tun` est absent, active **TUN/TAP** dans le panneau de ton hébergeur, redémarre le VPS, puis relance le script.

Mise à jour : `git pull && sudo ./install.sh` (tes extensions et tes appareils sont conservés).

### Gérer tes appareils

```bash
sudo caloo-peer add pc-portable   # crée l'appareil et affiche son QR code / sa config
sudo caloo-peer list
sudo caloo-peer show pc-portable
sudo caloo-peer remove pc-portable
```

Sur chaque appareil, installe **WireGuard**, puis scanne le QR code (téléphone) ou importe le fichier `.conf` (PC). Active le tunnel, puis ouvre **http://caloo.home** pour vérifier que tout marche.

## 2. Installer l'app Android

Chaque push sur GitHub compile l'app (workflow *Build Caloo*). Télécharge **`caloo.apk`** depuis la release **caloo-latest** :
<https://github.com/jules-mahounou/caloo/releases/tag/caloo-latest>

Dans l'app, ouvre **Réglages** et renseigne :
- Serveur : `http://10.8.0.1:8053`
- Token : celui affiché par `install.sh` (ou `sudo grep token /etc/caloo/config.json`)

### Mises à jour de l'APK sans désinstaller (recommandé)

Sans clé de signature fixe, chaque build est signé avec une clé différente, et Android refuse alors la mise à jour. Pour l'éviter, crée ta clé une seule fois sur un PC :

```bash
keytool -genkeypair -v -keystore caloo.jks -alias caloo -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 caloo.jks   # copie le résultat
```

Ensuite, dans GitHub, va dans **Settings → Secrets and variables → Actions**, et ajoute :
- `CALOO_KEYSTORE_B64` : le texte en base64 ;
- `CALOO_KEYSTORE_PASSWORD` : le mot de passe choisi.

**Ne mets jamais `caloo.jks` dans le dépôt.**

## 3. Les extensions

Une extension = un dossier dans `/var/lib/caloo/extensions/` (ou créé depuis l'app) :

```
mon-site/
  manifest.json
  site/            ← seulement pour l'action "site" : index.html, css, js, images…
```

```json
{
  "enabled": true,
  "priority": 0,
  "domains": ["monsite.home", "*.monsite.home"],
  "action": "site",
  "target": "",
  "ttl": 60,
  "options": { "spa": true }
}
```

| action | Ce que fait Caloo | `target` | `options` |
|---|---|---|---|
| `site` | sert le dossier `site/` (HTML/CSS/JS) | vide | `spa` : renvoie `index.html` pour les chemins inconnus |
| `proxy` | transmet les requêtes web à un autre serveur | `http://192.168.1.20:3000` | |
| `redirect` | redirige le navigateur | `https://exemple.com` | `keepPath` (vrai par défaut), `permanent` |
| `ip` | le domaine pointe vers une machine | `192.168.1.20` | |
| `dns` | demande à un autre serveur DNS | `1.1.1.1, 8.8.8.8` | |
| `block` | bloque le domaine | vide | |

- `domains` : `nom.exact` ou `*.domaine` (tous les sous-domaines).
- Si plusieurs extensions correspondent : la plus grande `priority` gagne, puis le domaine le plus précis.
- Les modifications faites à la main sur le serveur sont prises en compte en 5 secondes, sans redémarrage.
- Pour envoyer un site : depuis l'app, choisis un `.zip` (avec `index.html` à la racine ou dans un seul dossier).

### Ajouter un nouveau type d'action (pour plus tard)

Le noyau ne change pas. Écris un `object MonAction : Action` dans `server/src/main/kotlin/caloo/Actions.kt` (méthodes `dns()` et, si besoin, `http()`), puis ajoute une ligne `register("mon-action", MonAction)`.

## API (utilisée par l'app)

Toutes les routes demandent l'en-tête `Authorization: Bearer <token>`.

| | |
|---|---|
| `GET /api/status` | version, nombre d'extensions, erreurs |
| `GET /api/extensions` | liste des extensions |
| `PUT /api/extensions/{nom}` | crée/modifie (corps = manifest JSON) |
| `DELETE /api/extensions/{nom}` | supprime |
| `PUT /api/extensions/{nom}/site` | remplace le site (corps = .zip) |
| `GET /api/log` | 300 dernières requêtes DNS |
| `POST /api/reload` | recharge les extensions |

## Limites à connaître

- **HTTPS** : tes sites perso sont en `http://`. Un domaine HTTPS existant redirigé vers Caloo affichera une erreur de certificat.
- **DNS sécurisé du navigateur** (« DNS sécurisé » de Chrome, DoH de Firefox) : il contourne Caloo. Désactive-le sur tes appareils.
- **Appareils sans WireGuard** : un appareil branché sur le point d'accès de ton téléphone doit lui aussi avoir WireGuard activé pour profiter de Caloo.
- **VPS éteint** : tant que le tunnel est actif, la résolution DNS ne marche plus. Désactive le tunnel en attendant.
