# 081: account-authorized online eKey reference

## Pinned implementation (MIT)

Repository: https://github.com/MakcRe/KuGouMusicApi
Commit: `786fe07514b99158d19b24f7f5cea11977a1db67`

Exact upstream files included here (see `source-lock.json` for original paths, URLs and SHA-256):

- `upstream-song_url_new.js` ← `module/song_url_new.js`: POST `/v6/priv_url`, router `tracker.kugou.com`, resource body, tracker key, optional VIP token defaulting to empty, `vip` defaulting to 0.
- `request.js` ← `util/request.js`: platform-specific default query parameters, seconds clienttime, exact JSON body serialization and signing.
- `helper.js` ← `util/helper.js`: Android standard/lite signature salts; request body participates in signature.
- `index.js`, `config.json` ← corresponding `util/` files: platform-specific appid/clientver exports. These are published upstream application constants, not user credentials. None of these JS reference files are loaded by the game.
- `LICENSE-MakcRe.txt` ← `LICENSE`: MIT, Copyright (c) 2023 MakcRe.

Production `KugouOnlineKeys.kt` adapts only the relevant request construction. TLS is enforced instead of upstream song_url_new's HTTP base URL. IP forwarding/spoofing, ad/reward interfaces, account extraction, browser automation and anonymous fallback are NOT ported.

`reference-request.cjs` executes the actual pinned song_url_new module in a Node VM with deterministic synthetic time/account/device values. Kotlin request body bytes and independently signed Node query parameters must match for standard and lite modes. No network or upstream dependencies are executed by this oracle.

## Response evidence (not a checked-in real key)

- https://github.com/MakcRe/KuGouMusicApi/issues/159 : report includes an actual new-URL response entry with `hash`, `quality`, `status=1`, `_errno=0`, `fail_process=0`, and `info.en_ekey`, `info.tracker_status=1`, `info.tracker_type=full`. `en_hash` is different from the resource hash.
- https://github.com/MakcRe/KuGouMusicApi/issues/179 : report includes denied high-quality entries and an independently authorized `relate_goods` standard entry; keys may occur inside related entries.

Only field structure and authorization/hash-matching rules are used. No real account, signed CDN URL, token or eKey from either issue is bundled in this package. Tests use existing reproducible synthetic 079 keys/audio patterns.

## Why not `/v6/get_ekey`?

https://github.com/Aaron-gx/KGG-Decryptor/issues/2 identifies the Android endpoint but does not document its request/signature/response. Its shipped decryptor reads existing local DB/MMKV keys. 081 deliberately does NOT invent a get_ekey request. Instead it requests the same resource through the implemented, account-authenticated priv_url protocol and accepts the matching `en_ekey` only if returned with explicit full-play authorization.

Other inspected repositories:

- `BroNekoX/QueMusic` at `7c6173c94db48d251edbcb655ef2d9c6f698530e`: `api/platform/KugouApi.cpp::requestPrivUrl` corroborates TLS gateway + x-router, tracker body and body-inclusive query signature; its response URL parser is not used here. Not copied into production.
- `zues0077/lxserver` at `c48e0479e359d013f2c689dff269b13e30ddf24d`: no online KuGou eKey implementation found in inspected source archive.
- `Aaron-gx/KGG-Decryptor`, `rallait/kugou-kgg-decryptor`, `Show-o4210/kugou-unlock`, `skxxxkx666/Kugo-Music-Converter`: publicly described KuGou key retrieval is local DB/MMKV; QQ GetEVkey is not a KuGou protocol.

## Deliberate limitations

- Ported requests exist for both standard and concept identities, but no live account was available to validate either.
- Existing QR login does not expose a separate vip_token. Follow the reference's empty optional viptoken and vip=0; never invent membership. A server that requires additional authorization may refuse or omit en_ekey. This is surfaced as a failure, not bypassed.
- Header-only imports omit album_audio_id exactly as the upstream module does for an undefined ID. A platform requiring more resource metadata may refuse.
- Only exact `row.hash`/related-row hash equality with the KGG header hash is accepted. No unverified `en_hash` alias, different-quality key or unrelated first-result key is used. Some legitimate files may therefore fail closed.
- This is key retrieval for existing supported KGM-magic v5/KGG containers. It is NOT a new raw QQ MGG/MFLAC/musicex decoder, and does not make every response URL or file format supported.
- No online keys are persisted or globally cached; repeated jobs may request again. Java/Kotlin immutable strings cannot be reliably zeroized. Derived cipher bytes retain 079's existing cleanup behavior.
