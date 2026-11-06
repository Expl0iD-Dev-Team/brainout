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

myq() { docker exec -i "$MYSQL_CONTAINER" mysql -uroot -p"$MYSQL_ROOT_PW" "$1" 2>/dev/null; }

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
myq dev_game <<SQL
INSERT INTO game_servers (gamespace_id, game_name, game_server_name, \`schema\`, max_players, game_settings, server_settings)
VALUES ($GAMESPACE_ID, '$GAME', 'free', '{}', 32,
 '{"binary":"run.sh","ports":3,"max_players":32,"arguments":["--mode","free","--settings","server-free.json","--map","freeplay-maps.shuffle"],"token":{"authenticate":true,"username":"$SRV_USER","password":"$SRV_PASS","scopes":"$TOKEN_SCOPES"},"discover":$DISCOVER}',
 '{}')
ON DUPLICATE KEY UPDATE game_settings=VALUES(game_settings), max_players=VALUES(max_players);
SET @gsid = (SELECT game_server_id FROM game_servers WHERE gamespace_id=$GAMESPACE_ID AND game_name='$GAME' AND game_server_name='free');
INSERT INTO game_server_versions (gamespace_id, game_name, game_version, game_server_id, server_settings)
VALUES ($GAMESPACE_ID, '$GAME', '$VERSION', @gsid, '{}')
ON DUPLICATE KEY UPDATE server_settings=VALUES(server_settings);

-- The client does NOT go straight into a match: after login it joins the "lobby"
-- game server (CSFindLobby -> joinGame(..., "lobby", auto_create=true)), so it
-- must exist or the client errors with 404.
-- NOTE: --map takes a FILE PATH (MapSource does Gdx.files.absolute(map)), so it
-- is 'maps/lobby.map', not 'lobby'.
INSERT INTO game_servers (gamespace_id, game_name, game_server_name, \`schema\`, max_players, game_settings, server_settings)
VALUES ($GAMESPACE_ID, '$GAME', 'lobby', '{}', 32,
 '{"binary":"run.sh","ports":3,"max_players":32,"arguments":["--mode","lobby","--settings","server-lobby.json","--map","maps/lobby.map"],"token":{"authenticate":true,"username":"$SRV_USER","password":"$SRV_PASS","scopes":"$TOKEN_SCOPES"},"discover":$DISCOVER}',
 '{}')
ON DUPLICATE KEY UPDATE game_settings=VALUES(game_settings), max_players=VALUES(max_players);

SET @lid = (SELECT game_server_id FROM game_servers WHERE gamespace_id=$GAMESPACE_ID AND game_name='$GAME' AND game_server_name='lobby');
INSERT INTO game_server_versions (gamespace_id, game_name, game_version, game_server_id, server_settings)
VALUES ($GAMESPACE_ID, '$GAME', '$VERSION', @lid, '{}')
ON DUPLICATE KEY UPDATE server_settings=VALUES(server_settings);
INSERT INTO deployments (deployment_id, gamespace_id, game_name, game_version, deployment_hash, deployment_status)
VALUES ($DEPLOYMENT_ID, $GAMESPACE_ID, '$GAME', '$VERSION', 'local', 'delivered')
ON DUPLICATE KEY UPDATE deployment_status='delivered';
INSERT INTO game_deployments (gamespace_id, game_name, game_version, current_deployment, deployment_enabled)
VALUES ($GAMESPACE_ID, '$GAME', '$VERSION', $DEPLOYMENT_ID, 1)
ON DUPLICATE KEY UPDATE current_deployment=$DEPLOYMENT_ID, deployment_enabled=1;
SQL

echo "Done. Stage the deployment files with build-deployment.sh, then create a room:"
echo "  POST http://localhost:9508/create/$GAME/free/$VERSION  (access_token=..., settings={})"
