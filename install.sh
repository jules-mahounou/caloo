#!/usr/bin/env bash
# Caloo - installation / mise à jour tout-en-un (Ubuntu 22.04 / 24.04, VM ou conteneur LXC).
#
#   sudo ./install.sh
#
# Variables optionnelles :
#   CALOO_ENDPOINT=mon.vps.fr   adresse publique du VPS (détectée sinon)
#   CALOO_WG_PORT=51820         port UDP WireGuard
#   CALOO_WG_IF=wg0             nom de l'interface WireGuard
#   CALOO_WG_NET=10.8.0         réseau privé (/24)
#   CALOO_BUILD=1               compiler le serveur sur place au lieu de télécharger la version de GitHub
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RELEASE_URL="${CALOO_RELEASE_URL:-https://github.com/jules-mahounou/caloo/releases/download/caloo-latest/caloo-server.tar.gz}"
CONF_DIR=/etc/caloo
DATA_DIR=/var/lib/caloo
APP_DIR=/opt/caloo
ENV_FILE=$CONF_DIR/install.env
API_PORT=8053

say()  { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m[!] %s\033[0m\n' "$*"; }
die()  { printf '\n\033[1;31mErreur : %s\033[0m\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "lance le script avec sudo : sudo ./install.sh"
command -v apt-get >/dev/null || die "ce script est prévu pour Ubuntu / Debian"

# Réglages : ceux d'une installation précédente sont conservés.
[[ -f $ENV_FILE ]] && source "$ENV_FILE"
WG_IF="${CALOO_WG_IF:-${WG_IF:-wg0}}"
WG_NET="${CALOO_WG_NET:-${WG_NET:-10.8.0}}"
WG_PORT="${CALOO_WG_PORT:-${WG_PORT:-51820}}"
ENDPOINT="${CALOO_ENDPOINT:-${ENDPOINT:-}}"
WG_CONF=/etc/wireguard/$WG_IF.conf
WG_IP=$WG_NET.1
IN_CONTAINER=no
systemd-detect-virt -c -q 2>/dev/null && IN_CONTAINER=yes

# ---------------------------------------------------------------- paquets
say "Installation des paquets"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq wireguard-tools qrencode openjdk-17-jre-headless curl iproute2 iptables ca-certificates >/dev/null

# ---------------------------------------------------------------- WireGuard
say "WireGuard"
wg_kernel_ok() {
  ip link add caloo-wgtest type wireguard 2>/dev/null || return 1
  ip link del caloo-wgtest 2>/dev/null
  return 0
}

if ip link show "$WG_IF" &>/dev/null; then
  echo "Interface $WG_IF déjà active."
elif wg_kernel_ok; then
  echo "WireGuard noyau disponible."
elif command -v wireguard-go >/dev/null; then
  echo "WireGuard logiciel (wireguard-go) déjà installé."
else
  warn "WireGuard noyau indisponible (normal dans un conteneur LXC) : passage à wireguard-go."
  [[ -c /dev/net/tun ]] || die "ton conteneur n'a pas accès à /dev/net/tun.
  Active l'option « TUN/TAP » dans le panneau de ton hébergeur VPS (ou demande-lui
  de charger le module wireguard), redémarre le VPS puis relance ce script."
  had_go=no
  dpkg -s golang-go &>/dev/null && had_go=yes
  apt-get install -y -qq golang-go git make >/dev/null
  tmp=$(mktemp -d)
  git clone -q --depth 1 --branch 0.0.20230223 https://git.zx2c4.com/wireguard-go "$tmp/wg"
  make -s -C "$tmp/wg"
  install -m 755 "$tmp/wg/wireguard-go" /usr/local/bin/wireguard-go
  rm -rf "$tmp" /root/go /root/.cache/go-build
  # Go ne servait qu'à compiler wireguard-go : on le retire s'il n'était pas là avant.
  if [[ $had_go == no ]]; then
    apt-get purge -y -qq $(dpkg-query -W -f='${Package}\n' 'golang-*' 2>/dev/null) >/dev/null || true
  fi
  echo "wireguard-go installé."
fi

mkdir -p /etc/wireguard "$CONF_DIR/peers"
chmod 700 /etc/wireguard "$CONF_DIR/peers"
if [[ ! -f $WG_CONF ]]; then
  (umask 077
   wg genkey > /etc/wireguard/caloo-server.key
   wg pubkey < /etc/wireguard/caloo-server.key > /etc/wireguard/caloo-server.pub
   cat > "$WG_CONF" <<EOF
# caloo
[Interface]
Address = $WG_IP/24
ListenPort = $WG_PORT
PrivateKey = $(cat /etc/wireguard/caloo-server.key)
EOF
  )
  echo "Configuration $WG_CONF créée."
elif ! grep -q '^# caloo' "$WG_CONF"; then
  die "$WG_CONF existe déjà et n'a pas été créé par Caloo.
  Relance avec un autre nom : CALOO_WG_IF=wg1 CALOO_WG_NET=10.9.0 sudo ./install.sh"
fi

# Changement de port WireGuard (ex. sudo CALOO_WG_PORT=443 ./install.sh).
OLD_PORT="$(grep -oP '^ListenPort *= *\K[0-9]+' "$WG_CONF" || true)"
if [[ -n $OLD_PORT && $OLD_PORT != "$WG_PORT" ]]; then
  if ss -Hlnu "sport = :$WG_PORT" 2>/dev/null | grep -q .; then
    die "le port UDP $WG_PORT est déjà utilisé sur ce VPS : choisis-en un autre avec CALOO_WG_PORT=..."
  fi
  sed -i -E "s/^ListenPort *=.*/ListenPort = $WG_PORT/" "$WG_CONF"
  echo "Port WireGuard : $OLD_PORT → $WG_PORT."
fi

# Ports : Caloo écoute sur des ports discrets ; les ports 53 et 80 du tunnel y sont redirigés.
# Ainsi aucun conflit avec un nginx, Apache, Docker ou DNS déjà présent sur le VPS.
redirect_ok() {
  local r=(PREROUTING -i caloo-test -p udp --dport 53 -j REDIRECT --to-ports 10053)
  iptables -t nat -A "${r[@]}" 2>/dev/null || return 1
  iptables -t nat -D "${r[@]}" 2>/dev/null
  return 0
}
sed -i '/# caloo-redirect$/d' "$WG_CONF"
if redirect_ok; then
  MODE=redirect
  DNS_PORT=10053
  HTTP_PORT=10080
  # Règles tolérantes : « ajoute si absente » / « retire si présente », pour que WireGuard
  # ne puisse jamais échouer à cause d'elles au démarrage ou à l'arrêt.
  r() { echo "PREROUTING -i %i -d $WG_IP -p $1 --dport $2 -j REDIRECT --to-ports $3"; }
  up="" down=""
  for spec in "udp 53 $DNS_PORT" "tcp 53 $DNS_PORT" "tcp 80 $HTTP_PORT"; do
    rule="$(r $spec)"
    up+="iptables -t nat -C $rule 2>/dev/null || iptables -t nat -A $rule; "
    down+="iptables -t nat -D $rule 2>/dev/null || true; "
  done
  sed -i "/^PrivateKey/a PostUp = $up # caloo-redirect" "$WG_CONF"
  sed -i "/^PrivateKey/a PreDown = $down # caloo-redirect" "$WG_CONF"
  echo "Redirection 53→$DNS_PORT et 80→$HTTP_PORT dans le tunnel."
else
  MODE=direct
  DNS_PORT=53
  HTTP_PORT=80
  warn "iptables indisponible : Caloo écoutera directement sur les ports 53 et 80."
fi

# Laisse tes appareils se parler entre eux à travers le tunnel (peut échouer en LXC, sans gravité).
echo "net.ipv4.ip_forward=1" > /etc/sysctl.d/99-caloo.conf
sysctl -q -p /etc/sysctl.d/99-caloo.conf 2>/dev/null || warn "ip_forward non modifiable ici : tes appareils joindront le VPS mais pas forcément les uns les autres."

if [[ -z $ENDPOINT ]]; then
  ENDPOINT="$(curl -4 -fsS --max-time 6 https://api.ipify.org || true)"
  [[ -n $ENDPOINT ]] || ENDPOINT="$(ip -4 route get 1.1.1.1 | awk '{for(i=1;i<=NF;i++) if($i=="src") print $(i+1)}')"
  [[ -n $ENDPOINT ]] || die "adresse publique introuvable : relance avec CALOO_ENDPOINT=ip.du.vps sudo ./install.sh"
fi

cat > "$ENV_FILE" <<EOF
WG_IF=$WG_IF
WG_NET=$WG_NET
WG_PORT=$WG_PORT
ENDPOINT=$ENDPOINT
EOF

# Les appareils existants suivent l'adresse et le port du serveur (à rescanner s'ils changent).
CHANGED_PEERS=""
for f in "$CONF_DIR"/peers/*.conf; do
  [[ -f $f ]] || continue
  if ! grep -q "^Endpoint = $ENDPOINT:$WG_PORT\$" "$f"; then
    sed -i -E "s/^Endpoint *=.*/Endpoint = $ENDPOINT:$WG_PORT/" "$f"
    CHANGED_PEERS+=" $(basename "$f" .conf)"
  fi
done

if command -v ufw >/dev/null && ufw status | grep -q "Status: active"; then
  ufw allow "$WG_PORT/udp" >/dev/null
  ufw allow in on "$WG_IF" >/dev/null
  ufw route allow in on "$WG_IF" out on "$WG_IF" >/dev/null
  echo "Pare-feu ufw ouvert pour WireGuard."
fi

systemctl enable -q "wg-quick@$WG_IF"
# Redémarrage propre : on retire l'interface même si l'arrêt précédent a échoué.
systemctl stop "wg-quick@$WG_IF" 2>/dev/null || true
ip link del "$WG_IF" 2>/dev/null || true
if ! systemctl start "wg-quick@$WG_IF"; then
  echo "----- erreur de WireGuard -----"
  wg-quick up "$WG_IF" 2>&1 | tail -20 || true
  wg-quick down "$WG_IF" &>/dev/null || true
  die "WireGuard n'a pas démarré. Envoie les lignes ci-dessus pour diagnostic."
fi

# ---------------------------------------------------------------- serveur Caloo
systemctl stop caloo 2>/dev/null || true

if [[ ${CALOO_BUILD:-0} == 1 ]]; then
  say "Compilation du serveur Caloo sur place"
  apt-get install -y -qq openjdk-17-jdk-headless >/dev/null
  (cd "$REPO_DIR/server" && chmod +x gradlew && ./gradlew --no-daemon -q installDist)
  SRC="$REPO_DIR/server/build/install/caloo-server"
else
  say "Téléchargement du serveur Caloo (déjà compilé par GitHub)"
  tmp=$(mktemp -d)
  curl -fsSL --retry 3 "$RELEASE_URL" -o "$tmp/caloo-server.tar.gz" \
    || die "téléchargement impossible : $RELEASE_URL
  (ou compile sur place : CALOO_BUILD=1 sudo ./install.sh)"
  tar -xzf "$tmp/caloo-server.tar.gz" -C "$tmp"
  SRC="$tmp/caloo-server"
  [[ -x $SRC/bin/caloo-server ]] || die "archive du serveur invalide"
fi
rm -rf "$APP_DIR"
mkdir -p "$APP_DIR"
cp -r "$SRC/." "$APP_DIR/"
[[ ${CALOO_BUILD:-0} == 1 ]] || rm -rf "$tmp"
install -m 755 "$REPO_DIR/scripts/caloo-peer" /usr/local/bin/caloo-peer

id caloo &>/dev/null || useradd --system --home-dir "$DATA_DIR" --shell /usr/sbin/nologin caloo
mkdir -p "$DATA_DIR/extensions"
if [[ -z "$(ls -A "$DATA_DIR/extensions")" ]]; then
  cp -r "$REPO_DIR/examples/." "$DATA_DIR/extensions/"
  echo "Extensions d'exemple installées."
fi
chown -R caloo:caloo "$DATA_DIR"

if [[ ! -f $CONF_DIR/config.json ]]; then
  TOKEN="$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"
  cat > "$CONF_DIR/config.json" <<EOF
{
  "dnsBind": "$WG_IP",
  "dnsPort": $DNS_PORT,
  "httpBind": "$WG_IP",
  "httpPort": $HTTP_PORT,
  "apiBind": "$WG_IP",
  "apiPort": $API_PORT,
  "selfIp": "$WG_IP",
  "upstream": ["1.1.1.1", "9.9.9.9"],
  "extensionsDir": "$DATA_DIR/extensions",
  "maxSiteMb": 100,
  "token": "$TOKEN"
}
EOF
else
  # Mise à jour : on garde le token et tes réglages, seuls les ports suivent le mode choisi.
  sed -i -E "s/(\"dnsPort\": *)[0-9]+/\1$DNS_PORT/; s/(\"httpPort\": *)[0-9]+/\1$HTTP_PORT/" "$CONF_DIR/config.json"
fi
chown root:caloo "$CONF_DIR/config.json"
chmod 640 "$CONF_DIR/config.json"

# Vérifie que les ports de Caloo sont libres, et dit qui les occupe sinon.
busy=""
for p in "udp $DNS_PORT" "tcp $DNS_PORT" "tcp $HTTP_PORT" "tcp $API_PORT"; do
  set -- $p
  line="$(ss -Hln${1:0:1}p "sport = :$2" 2>/dev/null | grep -E "(\*|0\.0\.0\.0|\[::\]|$WG_IP):$2 " || true)"
  [[ -n $line ]] && busy+="  port $2/$1 : $(grep -oE 'users:\(\("[^"]+"' <<<"$line" | head -1 | cut -d'"' -f2)"$'\n'
done
[[ -z $busy ]] || die "ces ports sont déjà utilisés par un autre programme :
$busy  Libère-les puis relance le script."

CAPS=""
if [[ $MODE == direct ]]; then
  CAPS="AmbientCapabilities=CAP_NET_BIND_SERVICE
CapabilityBoundingSet=CAP_NET_BIND_SERVICE"
fi
# Les protections par « namespaces » de systemd échouent souvent dans un conteneur LXC.
HARDENING=""
if [[ $IN_CONTAINER == no ]]; then
  HARDENING="ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
ReadWritePaths=$DATA_DIR"
fi

cat > /etc/systemd/system/caloo.service <<EOF
[Unit]
Description=Caloo (DNS + sites perso)
After=network-online.target wg-quick@$WG_IF.service
Requires=wg-quick@$WG_IF.service

[Service]
User=caloo
ExecStart=$APP_DIR/bin/caloo-server $CONF_DIR/config.json
Environment="JAVA_OPTS=-Xmx128m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k"
Restart=always
RestartSec=3
NoNewPrivileges=true
$CAPS
$HARDENING

[Install]
WantedBy=multi-user.target
EOF

say "Démarrage de Caloo"
systemctl daemon-reload
systemctl enable -q caloo
systemctl restart caloo
ok=no
for _ in $(seq 1 20); do
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "http://$WG_IP:$API_PORT/api/status" || true)"
  [[ $code == 401 ]] && { ok=yes; break; }
  sleep 1
done
if [[ $ok != yes ]]; then
  echo
  timeout 5 systemctl status caloo --no-pager -n 0 || true
  echo "----- derniers messages de Caloo -----"
  timeout 5 journalctl -u caloo -n 30 --no-pager -o cat 2>/dev/null || true
  die "Caloo n'a pas démarré. Envoie les lignes ci-dessus pour diagnostic."
fi
echo "Caloo répond sur http://$WG_IP:$API_PORT"

# ---------------------------------------------------------------- nettoyage
say "Nettoyage"
pkill -f KotlinCompileDaemon 2>/dev/null || true
rm -rf "$REPO_DIR/server/build" "$REPO_DIR/server/.gradle" "$REPO_DIR/server/.kotlin"
if [[ ${CALOO_BUILD:-0} != 1 ]]; then
  [[ -d /root/.gradle ]] && rm -rf /root/.gradle && echo "Cache de compilation /root/.gradle supprimé."
  # Le JDK ne servait qu'à compiler : on le retire s'il n'entraîne rien d'autre avec lui.
  if dpkg -s openjdk-17-jdk-headless &>/dev/null \
     && [[ "$(apt-get -s remove openjdk-17-jdk-headless | grep -c '^Remv')" == 1 ]]; then
    apt-mark manual openjdk-17-jre-headless >/dev/null
    apt-get remove -y -qq openjdk-17-jdk-headless >/dev/null && echo "JDK de compilation retiré (Java runtime conservé)."
  fi
fi
apt-get clean
echo "Espace utilisé par Caloo : $(du -sh "$APP_DIR" | cut -f1) (programme) + $(du -sh "$DATA_DIR" | cut -f1) (extensions)."

# ---------------------------------------------------------------- premier appareil
if [[ -z "$(ls -A "$CONF_DIR/peers")" ]]; then
  say "Création de ton premier appareil : « telephone »"
  caloo-peer add telephone
fi

if [[ -n $CHANGED_PEERS ]]; then
  say "Port ou adresse du serveur modifié : rescanne ces appareils"
  for p in $CHANGED_PEERS; do caloo-peer show "$p"; done
  warn "Dans l'app WireGuard, supprime l'ancien tunnel puis importe le nouveau QR code ci-dessus."
fi

TOKEN="$(grep -o '"token": *"[^"]*"' "$CONF_DIR/config.json" | cut -d'"' -f4)"
say "Caloo est prêt !"
cat <<EOF

  1. Sur ton téléphone, installe l'app WireGuard et scanne le QR code ci-dessus
     (ou réaffiche-le avec : sudo caloo-peer show telephone). Active le tunnel.
  2. Ouvre http://caloo.home dans le navigateur du téléphone : la page d'accueil doit s'afficher.
  3. Installe l'app Caloo (APK des « Releases » GitHub) et renseigne :
       Serveur : http://$WG_IP:$API_PORT
       Token   : $TOKEN

  Autres appareils : sudo caloo-peer add pc-portable   (puis list / show / remove)
  Mise à jour      : git pull && sudo ./install.sh
  Journal          : journalctl -u caloo -f
EOF
