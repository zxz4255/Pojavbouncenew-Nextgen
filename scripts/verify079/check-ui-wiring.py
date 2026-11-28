#!/usr/bin/env python3
"""Source wiring assertions for the full MusicScreen/ModuleMusic, not a GUI execution test."""
from pathlib import Path
root=Path(__file__).resolve().parents[2]
r=root/'src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/misc/music'
module=(r/'ModuleMusic.kt').read_text()
screen=(r/'MusicScreen.kt').read_text()
island=(r/'onyx/hud/Island.kt').read_text()
mini=(r/'MusicIsland.kt').read_text()
manager=(r/'MusicManager.kt').read_text()
assert 'val showIsland = boolean("ShowIsland", true)' in module
assert module.count('IslandGroup.showIsland.set(')==1
setter=module.split('fun setIslandVisible(value: Boolean)',1)[1].split('private fun openKey',1)[0]
assert 'IslandGroup.showIsland.set(value)' in setter and 'ConfigSystem.store(' in setter and 'ModuleManager.modulesConfig' in setter
open_fn=module.split('fun requestOpen()',1)[1].split('object Transport',1)[0]
assert 'showIsland' not in open_fn and 'setIslandVisible' not in open_fn
assert 'ModuleMusic.setIslandVisible(it)' in screen and '"音乐 Island"' in screen
assert 'val useIsland = ModuleMusic.islandPillActive()' in screen
assert 'val geometryK = if (useIsland) k else 1f' in screen
assert 'renderer.alphaPush(if (useIsland) 1f else k)' in screen
assert 'if (useIsland) drawMorphArtwork(renderer, k)' in screen
assert 'drawMorphWindow(renderer, geometryK, radius)\n        renderer.alphaPop()' in screen
assert 'returnMini = ModuleMusic.islandPillActive() && MusicIsland.hasTrack()' in screen
assert 'ModuleMusic.islandPillActive() || MusicIsland.hasTrack()' not in island
assert 'if (!ModuleMusic.islandPillActive())' in island and 'if (!ModuleMusic.islandPillActive())' in mini
assert 'KugouClient.acceptLogin(kg, requestedConcept)' in manager and 'KugouClient.stream(song, streamLevel)' in manager
assert 'if (accountFailure != null && accountFailure.kind != KugouPlayback.FailureKind.NETWORK) throw accountFailure' in manager
assert 'KugouClient.stream(song, "standard")' in manager
assert 'MusicAudioParserFactory.isKugouEncrypted(part)' in manager
print('PASS: full-screen/module source wiring: persistent Island switch, open leaves it unchanged, non-Island fade/no morph artwork, guarded return; signed account errors preserved, encrypted-body standard-stream retry wired')

assert 'manager.importKggFiles()' in screen and 'manager.importedSongs()' in screen
assert '"查看已解密歌曲"' in screen and '"扫描并解密本地文件"' in screen
assert 'kugouLocal.decrypt(part, clearPart' in manager
assert 'if (decrypted) fileHash(part)' in manager
assert 'if (song.provider == MusicProvider.LOCAL) throw originalResult.exceptionOrNull()!!' in manager
local_branch=manager.split('if (song.provider == MusicProvider.LOCAL) {',1)[1].split('if (!isUsable(file))',1)[0]
assert 'validateDecodableAudio(file, serial)' in local_branch and 'return' in local_branch
assert 'discardCache' not in local_branch
assert 'MusicProvider.BILIBILI || song.provider == MusicProvider.LOCAL' in manager
print('PASS: 079 source wiring: local import/list UI, native preflight, no local deletion/fallback/remote lyrics, online decrypt before plaintext cache proof')
