#!/usr/bin/env bash
# Provision the Anthill backend (anthill-dev stack) to spawn the Brain/Out
# dedicated server for gamespace 1 (brainout:desktop) / game "brainout" /
# version "valpha2". Idempotent. Run after the stack is up and brainout is
# registered (see docs/OnlinePlatformLocal.md).
#
# It writes two DBs via the `mysql` container:
#   dev_game  - game_servers, game_server_versions, deployments, game_deployments
#   dev_login - a dev:<server> credential + account + scopes for the server-side
#               token the master mints and passes to each spawned server.
#
# Defaults match anthill-dev/dev/docker-compose.yml; override via env vars.
set -euo pipefail

MYSQL_CONTAINER="${MYSQL_CONTAINER:-mysql}"
MYSQL_ROOT_PW="${MYSQL_ROOT_PW:-Root123}"
# login's passwords_salt (deploy.sh reads it from docker-compose.yml)
SALT="${SALT:-${PASSWORDS_SALT:-t6YJbMTvMRnYyPW7WfZC2tGXUsJwy252pU0OiCM5}}"
GAMESPACE_ID="${GAMESPACE_ID:-1}"
GAME="${GAME:-brainout}"
VERSION="${VERSION:-valpha2}"
DEPLOYMENT_ID="${DEPLOYMENT_ID:-1}"
SRV_USER="${SRV_USER:-brainout_server}"   # dev:<SRV_USER>
# The account can sign in through the public login service and its scopes can
# rewrite any profile, so there is no default password (deploy.sh generates one).
SRV_PASS="${SRV_PASS:?SRV_PASS is not set (deploy.sh generates it in state/secrets.env)}"

# scopes the server-side token may carry (must cover ServerConstants.Online.EXTEND_SCOPES
# so the server can extend joining players' tokens), plus auth_non_unique for the
# master's non-unique authenticate.
SRV_SCOPES="auth_non_unique,game,profile,profile_write,profile_private,promo,event_profile_write,message_listen,store_order,group_create,group_write,lb_arbitrary_account,group,message_authoritative,event_write,event_join,party_create,profile_multi,market,market_post_order,market_update_item,market_delete_order"
# what the minted server token requests (granted = requested ∩ account scopes)
TOKEN_SCOPES="game,profile,profile_write,store_order,message_authoritative,party_create,profile_private,promo,event_profile_write,message_listen,group_create,group_write,lb_arbitrary_account,group,event_write,event_join,profile_multi,market,market_post_order,market_update_item,market_delete_order"
# services the master discovers (network=external) and passes as discovery_services
DISCOVER='["login","game","message","profile","leaderboard","report","social","store","event"]'

CRED="dev:${SRV_USER}"
# HMACSHA256(key=credential+salt, msg=password) -> base64  (PasswordsModel default algo)
PY="$(command -v python3 || command -v python)"
HASH=$(CRED="$CRED" SALT="$SALT" PW="$SRV_PASS" "$PY" -c \
    'import base64,hashlib,hmac,os;print(base64.b64encode(hmac.new((os.environ["CRED"]+os.environ["SALT"]).encode(),os.environ["PW"].encode(),hashlib.sha256).digest()).decode())')

# real SQL errors stay visible; only the client's known noise is dropped
myq() {
    docker exec -i "$MYSQL_CONTAINER" mysql -uroot -p"$MYSQL_ROOT_PW" "$1" 2>&1 \
        | grep -vE "Using a password on the command line|Found option without preceding group" || true
}

echo "== dev_login: server dev account ($CRED) + scopes =="
myq dev_login <<SQL
INSERT INTO credential_passwords (credential, algorithm, password)
VALUES ('$CRED','HMACSHA256','$HASH')
ON DUPLICATE KEY UPDATE password=VALUES(password), algorithm=VALUES(algorithm);
INSERT INTO accounts (account_info) SELECT '{}' FROM DUAL
  WHERE NOT EXISTS (SELECT 1 FROM account_credentials WHERE credential='$CRED');
SET @aid = (SELECT account_id FROM account_credentials WHERE credential='$CRED');
SET @aid = IFNULL(@aid, LAST_INSERT_ID());
INSERT IGNORE INTO account_credentials (credential, account_id) VALUES ('$CRED', @aid);
DELETE FROM account_access WHERE account_id=@aid AND gamespace_id=$GAMESPACE_ID;
INSERT INTO account_access (account_id, gamespace_id, scopes) VALUES (@aid, $GAMESPACE_ID, '$SRV_SCOPES');
SQL

echo "== dev_game: game server / version / deployment =="
# Bots topping up a Quick Play match ("bots" in the server settings, like the
# original "Run Server Game Phase.bat"); handy while only a few friends play.
BOTS_MAIN="${BOTS_MAIN:-6}"

# game_server <name> <max players> <server settings JSON> <server arguments JSON>
# The name is what the client asks the game master for (Matchmaking.FindGame).
# --map/--maps take FILE PATHS relative to the deployment (MapSource uses
# Gdx.files.absolute), e.g. maps/lobby.map rather than lobby.
game_server() {
    cat <<SQL
INSERT INTO game_servers (gamespace_id, game_name, game_server_name, \`schema\`, max_players, game_settings, server_settings)
VALUES ($GAMESPACE_ID, '$GAME', '$1', '{}', $2,
 '{"binary":"run.sh","ports":3,"max_players":$2,"arguments":$4,"token":{"authenticate":true,"username":"$SRV_USER","password":"$SRV_PASS","scopes":"$TOKEN_SCOPES"},"discover":$DISCOVER}',
 '$3')
ON DUPLICATE KEY UPDATE game_settings=VALUES(game_settings), max_players=VALUES(max_players), server_settings=VALUES(server_settings);
SET @gsid = (SELECT game_server_id FROM game_servers WHERE gamespace_id=$GAMESPACE_ID AND game_name='$GAME' AND game_server_name='$1');
INSERT INTO game_server_versions (gamespace_id, game_name, game_version, game_server_id, server_settings)
VALUES ($GAMESPACE_ID, '$GAME', '$VERSION', @gsid, '$3')
ON DUPLICATE KEY UPDATE server_settings=VALUES(server_settings);
SQL
}

{
    # After login the client joins the lobby (CSFindLobby), so without it nothing works.
    game_server lobby    32 '{}' '["--mode","lobby","--settings","server-lobby.json","--map","maps/lobby.map"]'
    # Lobby -> Quick Play (QuickPlayOptionsMenu): team modes over the map rotation.
    game_server main     16 "{\"bots\":$BOTS_MAIN}" '["--settings","server-settings.json","--maps","maps-set.json"]'
    # Not provisioned: "custom" (Steam Workshop maps, downloaded through the Steam
    # API, which this build doesn't have) and "editor"/"editor2" (map editors).
    # Lobby -> Free Play (CSFreeplaySolo); four players, as in the original setup.
    game_server freeplay  4 '{}' '["--mode","free","--settings","server-free.json","--map","freeplay-maps.shuffle"]'
    # Duel offered after a lobby challenge (CSDuel).
    game_server duel      2 '{}' '["--mode","duel","--settings","server-duel.json","--map","maps/duel.map"]'

    cat <<SQL
-- Servers earlier deploys created that can't work: "free" (the client asks for
-- "freeplay") and "custom" (needs Steam). Rooms reference servers, so drop theirs first.
DELETE r FROM rooms r JOIN game_servers s ON s.game_server_id = r.game_server_id
 WHERE s.gamespace_id=$GAMESPACE_ID AND s.game_name='$GAME' AND s.game_server_name IN ('free','custom');
DELETE v FROM game_server_versions v JOIN game_servers s ON s.game_server_id = v.game_server_id
 WHERE s.gamespace_id=$GAMESPACE_ID AND s.game_name='$GAME' AND s.game_server_name IN ('free','custom');
DELETE FROM game_servers
 WHERE gamespace_id=$GAMESPACE_ID AND game_name='$GAME' AND game_server_name IN ('free','custom');

INSERT INTO deployments (deployment_id, gamespace_id, game_name, game_version, deployment_hash, deployment_status)
VALUES ($DEPLOYMENT_ID, $GAMESPACE_ID, '$GAME', '$VERSION', 'local', 'delivered')
ON DUPLICATE KEY UPDATE deployment_status='delivered';
INSERT INTO game_deployments (gamespace_id, game_name, game_version, current_deployment, deployment_enabled)
VALUES ($GAMESPACE_ID, '$GAME', '$VERSION', $DEPLOYMENT_ID, 1)
ON DUPLICATE KEY UPDATE current_deployment=$DEPLOYMENT_ID, deployment_enabled=1;
SQL
} | myq dev_game

echo "game servers: $(echo "SELECT GROUP_CONCAT(game_server_name ORDER BY game_server_name) FROM game_servers WHERE gamespace_id=$GAMESPACE_ID AND game_name='$GAME';" | myq dev_game | tail -1)"
