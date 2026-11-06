#!/usr/bin/env bash
# Private CA + HTTPS certificate for a backend that has no domain name.
#
#   tls.sh <dir> <address>[,<address>...]
#
# <address> is an IPv4 address or a host name; the first one is the public
# address clients use. Creates in <dir>:
#   ca.key, ca.crt        the CA (10 years). Created once and never replaced:
#                         ca.crt is built into the APK, so a new CA means a new APK.
#   server.key, server.crt  the certificate nginx serves (5 years). Re-issued
#                         when the address list changes or it expires within 30 days.
#
# Keep ca.key private and backed up: whoever has it can impersonate the server
# to every installed APK.
set -euo pipefail

DIR="${1:?usage: tls.sh <dir> <address>[,<address>...]}"
ADDRESSES="${2:?usage: tls.sh <dir> <address>[,<address>...]}"

command -v openssl >/dev/null || { echo "openssl not found" >&2; exit 1; }
mkdir -p "$DIR"
cd "$DIR"
umask 077

san=""
i_ip=0
i_dns=0
IFS=',' read -ra items <<< "$ADDRESSES"
for a in "${items[@]}"; do
    a="$(echo "$a" | tr -d '[:space:]')"
    [ -z "$a" ] && continue
    if [[ "$a" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
        i_ip=$((i_ip + 1)); san+="IP.$i_ip = $a"$'\n'
    else
        i_dns=$((i_dns + 1)); san+="DNS.$i_dns = $a"$'\n'
    fi
done
[ -n "$san" ] || { echo "no addresses given" >&2; exit 1; }

# config files instead of -subj: Git Bash on Windows rewrites "/CN=..." as a path
cat > ca.cnf <<'EOF'
[req]
distinguished_name = dn
prompt = no
x509_extensions = ext
[dn]
CN = BrainOut private CA
[ext]
basicConstraints = critical, CA:TRUE, pathlen:0
keyUsage = critical, keyCertSign, cRLSign
subjectKeyIdentifier = hash
EOF

if [ ! -f ca.key ] || [ ! -f ca.crt ]; then
    echo "creating CA"
    openssl req -x509 -new -newkey rsa:4096 -nodes -sha256 -days 3650 \
        -keyout ca.key -out ca.crt -config ca.cnf
fi

cat > server.cnf <<EOF
[req]
distinguished_name = dn
prompt = no
[dn]
CN = ${items[0]}
[ext]
basicConstraints = critical, CA:FALSE
keyUsage = critical, digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = @san
authorityKeyIdentifier = keyid
[san]
${san}
EOF

reissue=1
if [ -f server.crt ] && [ -f server.key ] && [ -f server.san ] \
   && [ "$(cat server.san)" = "$(printf '%s' "$san")" ] \
   && openssl x509 -checkend $((30 * 86400)) -noout -in server.crt >/dev/null; then
    reissue=0
fi

if [ "$reissue" = 1 ]; then
    echo "issuing server certificate for: $ADDRESSES"
    openssl req -new -newkey rsa:2048 -nodes -keyout server.key -out server.csr -config server.cnf
    openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
        -sha256 -days 1825 -extfile server.cnf -extensions ext -out server.crt
    rm -f server.csr
    printf '%s' "$san" > server.san
else
    echo "server certificate is current"
fi

chmod 644 ca.crt server.crt
openssl verify -CAfile ca.crt server.crt
