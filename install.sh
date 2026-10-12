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
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONF_DIR=/etc/caloo
DATA_DIR=/var/lib/caloo
APP_DIR=/opt/caloo
ENV_FILE=$CONF_DIR/install.env

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
IN_CONTAINER=no
systemd-detect-virt -c -q 2>/dev/null && IN_CONTAINER=yes

# ---------------------------------------------------------------- paquets
say "Installation des paquets"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq wireguard-tools qrencode openjdk-17-jdk-headless curl iproute2 ca-certificates >/dev/null

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
  apt-get install -y -qq golang-go git make >/dev/null
  tmp=$(mktemp -d)
  git clone -q --depth 1 --branch 0.0.20230223 https://git.zx2c4.com/wireguard-go "$tmp/wg"
  make -s -C "$tmp/wg"
  install -m 755 "$tmp/wg/wireguard-go" /usr/local/bin/wireguard-go
  rm -rf "$tmp"
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
Address = $WG_NET.1/24
ListenPort = $WG_PORT
PrivateKey = $(cat /etc/wireguard/caloo-server.key)
EOF
  )
  echo "Configuration $WG_CONF créée."
elif ! grep -q '^# caloo' "$WG_CONF"; then
  die "$WG_CONF existe déjà et n'a pas été créé par Caloo.
  Relance avec un autre nom : CALOO_WG_IF=wg1 CALOO_WG_NET=10.9.0 sudo ./install.sh"
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

if command -v ufw >/dev/null && ufw status | grep -q "Status: active"; then
  ufw allow "$WG_PORT/udp" >/dev/null
  ufw allow in on "$WG_IF" >/dev/null
  ufw route allow in on "$WG_IF" out on "$WG_IF" >/dev/null
  echo "Pare-feu ufw ouvert pour WireGuard."
fi

systemctl enable -q "wg-quick@$WG_IF"
systemctl restart "wg-quick@$WG_IF" || die "WireGuard n'a pas démarré : journalctl -u wg-quick@$WG_IF"

# ---------------------------------------------------------------- serveur Caloo
say "Compilation du serveur Caloo (quelques minutes la première fois)"
(cd "$REPO_DIR/server" && chmod +x gradlew && ./gradlew --no-daemon -q installDist)
rm -rf "$APP_DIR"
mkdir -p "$APP_DIR"
cp -r "$REPO_DIR/server/build/install/caloo-server/." "$APP_DIR/"
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
  "dnsBind": "$WG_NET.1",
  "dnsPort": 53,
  "httpBind": "$WG_NET.1",
  "httpPort": 80,
  "apiBind": "$WG_NET.1",
  "apiPort": 8053,
  "selfIp": "$WG_NET.1",
  "upstream": ["1.1.1.1", "9.9.9.9"],
  "extensionsDir": "$DATA_DIR/extensions",
  "maxSiteMb": 100,
  "token": "$TOKEN"
}
EOF
fi
chown root:caloo "$CONF_DIR/config.json"
chmod 640 "$CONF_DIR/config.json"

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
Environment=JAVA_OPTS=-Xmx160m
Restart=always
RestartSec=3
AmbientCapabilities=CAP_NET_BIND_SERVICE
CapabilityBoundingSet=CAP_NET_BIND_SERVICE
NoNewPrivileges=true
$HARDENING

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable -q caloo
systemctl restart caloo
sleep 4
systemctl is-active -q caloo || die "Caloo n'a pas démarré : journalctl -u caloo -n 50"

# ---------------------------------------------------------------- premier appareil
if [[ -z "$(ls -A "$CONF_DIR/peers")" ]]; then
  say "Création de ton premier appareil : « telephone »"
  caloo-peer add telephone
fi

TOKEN="$(grep -o '"token": *"[^"]*"' "$CONF_DIR/config.json" | cut -d'"' -f4)"
say "Caloo est prêt !"
cat <<EOF

  1. Sur ton téléphone, installe l'app WireGuard et scanne le QR code ci-dessus
     (ou réaffiche-le avec : sudo caloo-peer show telephone). Active le tunnel.
  2. Ouvre http://caloo.home dans le navigateur du téléphone : la page d'accueil doit s'afficher.
  3. Installe l'app Caloo (APK des « Releases » GitHub) et renseigne :
       Serveur : http://$WG_NET.1:8053
       Token   : $TOKEN

  Autres appareils : sudo caloo-peer add pc-portable   (puis list / show / remove)
  Mise à jour      : git pull && sudo ./install.sh
  Journal          : journalctl -u caloo -f
EOF
