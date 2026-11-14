#!/usr/bin/env bash
# Seed a starter economy for gamespace 1 (brainout:desktop) into the local
# anthill-dev stack. Idempotent. See docs/OnlineEconomy.md for the model.
#
# NOTE: the desktop client has the IAP store DISABLED
# (DesktopEnvironment.storeEnabled()==false), so on desktop the economy is
# profile-centric — the wallet (gears/skillpts/nuclear-material), level, loadout
# and inventory live in the *profile* service, granted/updated by the game
# server. The store currencies/store below are still authored because Steam /
# Android builds use the IAP store and the profile prices reference them.
set -euo pipefail

MYSQL_CONTAINER="${MYSQL_CONTAINER:-mysql}"
MYSQL_ROOT_PW="${MYSQL_ROOT_PW:-Root123}"
GAMESPACE_ID="${GAMESPACE_ID:-1}"

# The catalog carries Cyrillic titles, so the connection must be utf8mb4 or the
# client gets mojibake (UTF-8 bytes stored as if they were latin1). The client
# flag alone is not enough here — prepend SET NAMES to every batch.
# Only the known container noise is filtered — real SQL errors must stay visible,
# otherwise a broken seed looks like a successful one.
myq() {
    { echo "SET NAMES utf8mb4;"; cat; } \
        | docker exec -i "$MYSQL_CONTAINER" mysql -uroot -p"$MYSQL_ROOT_PW" "$1" 2>&1 \
        | grep -vE "Using a password on the command line|Found option without preceding group" || true
}

echo "== dev_store: currencies + default store =="
myq dev_store <<SQL
INSERT INTO currencies (gamespace_id, currency_name, currency_title, currency_format, currency_symbol, currency_label) VALUES
 ($GAMESPACE_ID,'gears','Gears','{0}','gears','gears'),
 ($GAMESPACE_ID,'skillpts','Skill Points','{0}','skillpts','skillpts'),
 ($GAMESPACE_ID,'nuclear-material','Nuclear Material','{0}','nuke','nuclear-material'),
 ($GAMESPACE_ID,'ru','RU','{0}','RU','ru'),
 ($GAMESPACE_ID,'USD','US Dollar','\${0}','\$','usd')
ON DUPLICATE KEY UPDATE currency_title=VALUES(currency_title), currency_symbol=VALUES(currency_symbol), currency_label=VALUES(currency_label);

INSERT INTO stores (gamespace_id, store_name, store_campaign_scheme) VALUES ($GAMESPACE_ID,'main','{}')
ON DUPLICATE KEY UPDATE store_name=VALUES(store_name);
SQL

# ---------------------------------------------------------------------------
# Catalog.
#
# Items are priced with "offline-price" (in-game currency taken from the profile
# wallet) rather than an IAP tier, because no payment provider is wired up:
# StoreMenu.purchaseOffline() spends stats directly and needs no store component.
# A tier row is still required - items.item_tier is NOT NULL with an FK.
#
# item_public_data  -> what the client renders: title/description per language
#                      ("EN" is Environment.getDefaultLanguage()), offline-price.
# item_private_data -> ServerRewards: {"actions":[{"action":..,"id":..,"amount":..}]}
#                      applied by PlayerClient.applyOrderContents on a paid order.
#                      action: unlock (grant content) | addstat (add currency/stat)
# ---------------------------------------------------------------------------
echo "== dev_store: category + tier + items =="
myq dev_store <<SQL
INSERT INTO categories (gamespace_id, category_name, category_public_item_scheme, category_private_item_scheme)
VALUES ($GAMESPACE_ID, 'default', '{}', '{}')
ON DUPLICATE KEY UPDATE category_public_item_scheme=VALUES(category_public_item_scheme);
SET @cat = (SELECT category_id FROM categories WHERE category_name='default');
SET @store = (SELECT store_id FROM stores WHERE gamespace_id=$GAMESPACE_ID AND store_name='main');

-- tiers has only a non-unique KEY on (gamespace_id, store_id, tier_name), so
-- ON DUPLICATE KEY never fires and repeat runs would pile up duplicate tiers
-- (and then SET @tier below fails with "Subquery returns more than 1 row").
INSERT INTO tiers (gamespace_id, store_id, tier_name, tier_title, tier_product, tier_prices)
SELECT $GAMESPACE_ID, @store, 'tier-small', 'Small', 'brainout_small', '{"USD": 99}' FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM tiers WHERE gamespace_id=$GAMESPACE_ID AND store_id=@store AND tier_name='tier-small');
SET @tier = (SELECT tier_id FROM tiers WHERE gamespace_id=$GAMESPACE_ID AND store_id=@store AND tier_name='tier-small' LIMIT 1);

INSERT INTO items (gamespace_id, store_id, item_category, item_name, item_public_data, item_private_data, item_tier, item_enabled)
VALUES
 ($GAMESPACE_ID, @store, @cat, 'gears-pack-small',
  '{"title":{"EN":"Crate of Gears","RU":"Ящик шестерёнок"},"description":{"EN":"+1000 gears","RU":"+1000 шестерёнок"},"offline-price":{"currency":"skillpts","amount":100}}',
  '{"actions":[{"action":"addstat","id":"gears","amount":1000}]}', @tier, 1),
 ($GAMESPACE_ID, @store, @cat, 'nuclear-pack-small',
  '{"title":{"EN":"Nuclear Material","RU":"Ядерный материал"},"description":{"EN":"+50 nuclear material","RU":"+50 ядерного материала"},"offline-price":{"currency":"gears","amount":2000}}',
  '{"actions":[{"action":"addstat","id":"nuclear-material","amount":50}]}', @tier, 1),
 ($GAMESPACE_ID, @store, @cat, 'weapon-toz34',
  '{"title":{"EN":"TOZ-34","RU":"ТОЗ-34"},"description":{"EN":"Double-barrel shotgun","RU":"Двуствольное ружьё"},"offline-price":{"currency":"gears","amount":1500}}',
  '{"actions":[{"action":"unlock","id":"sl-pri-toz34","amount":1}]}', @tier, 1)
ON DUPLICATE KEY UPDATE item_public_data=VALUES(item_public_data), item_private_data=VALUES(item_private_data), item_enabled=1;
SQL

echo "Done. Currency ids match the client (core/Constants.User): gears, skillpts,"
echo "nuclear-material; plus ru / USD for IAP tiers. Profile wallet/loadout are"
echo "seeded per-account via the profile service (see docs/OnlineEconomy.md)."
