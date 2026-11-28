#!/usr/bin/env python3
"""081 source wiring + pinned upstream integrity; not a full UI/runtime test."""
from pathlib import Path
import hashlib,json
root=Path(__file__).resolve().parents[2]
r=root/'src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/misc/music'
manager=(r/'MusicManager.kt').read_text()
client=(r/'KugouClient.kt').read_text()
local=(r/'KugouLocalFiles.kt').read_text()
wire=(r/'KugouOnlineKeys.kt').read_text()
screen=(r/'MusicScreen.kt').read_text()
assert 'KugouLocalFiles(musicDir(), KugouClient::onlineKey)' in manager
assert 'keys()[hash.lowercase()] ?: onlineLookup?.invoke(hash.lowercase(), cancel)' in local
assert 'shuttingDown || !accountCurrent()' in manager
assert 'serial != playSerial || shuttingDown || !accountCurrent()' in manager
assert 'catch (e: KugouPlayback.Failure) { throw e }\n                catch (e: IOException)' in manager
assert 'accountEpoch++' in client and client.count('accountEpoch++')==2
assert 'accountEpoch == snapshot.epoch' in client and 'accountEpoch == before.epoch' in client
assert 'if (snapshot.uid <= 0L && snapshot.token.isBlank() && !snapshot.concept) return null' in client
assert 'KGG 缺钥时自动在线取钥' in screen
assert '"/v6/priv_url"' in wire and '"/v6/get_ekey"' not in wire
assert 'https://gateway.kugou.com' in wire
for forbidden in ['println(', 'logger.', 'writeText(', 'FileOutputStream', 'X-Forwarded-For', 'X-Real-IP']:
    assert forbidden not in wire,forbidden
for entry in json.loads((root/'references/kgg081/source-lock.json').read_text())['files']:
    path=root/'references/kgg081'/entry['local']
    assert hashlib.sha256(path.read_bytes()).hexdigest()==entry['sha256'],str(path)
print('PASS: 081 production wiring, local-first fallback, account-generation/cancellation, original API failure classification, UI notice, TLS/no-key-persistence/no-spoof guards; pinned upstream source hashes verified')
