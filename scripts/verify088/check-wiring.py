#!/usr/bin/env python3
from pathlib import Path
import json,re
root=Path(__file__).resolve().parents[2]
k=root/'src/main/kotlin/net/ccbluex/liquidbounce'
m=(k/'features/module/modules/render/cs2mvp/ModuleCs2Mvp.kt').read_text()
r=(k/'features/module/modules/render/cs2mvp/Cs2MvpRenderer.kt').read_text()
spec=json.loads((root/'licenses/cs2mvp088/source-spec.json').read_text())['measurements']
assert f'const val WIDTH = {spec["non_premier_width"]}f' in r
assert f'const val HEIGHT = {spec["height"]}f' in r
assert f'const val AVATAR = {spec["avatar"]}' in r
assert f'const val NAME_WIDTH = {spec["name_width"]}f' in r
for value in ['96C8FA','EABE54','09283D','422E08']:assert '0x'+value in r
assert '21f + 38f' in r and '28f' in r and '18f' in r and 'band(background, .85f)' in r
manager=(k/'features/module/ModuleManager.kt').read_text()
assert manager.count('            ModuleCs2Mvp,')==1
for token in ['if (event.isCancelled)', 'event.origin != TransferOrigin.INCOMING','mc.execute {',
 'mc.level !== capturedLevel','mc.player !== capturedPlayer','activeEpoch != capturedEpoch','packet.eventId != EntityEvent.DEATH',
 'target.health <= 0f','!FriendManager.isFriend(entity)','!local.isAlliedTo(entity)',
 'private val worldChangeHandler','mc.gui.hud.isHidden','mc.gui.screen() != null',
 'Attribution.AUTO -> VfpCompatibility1_8.INSTANCE.is1_8ConnectionActive']:
 assert token in m,token
assert 'MinecraftShortcuts' in (root/'scripts/verify088/hosts.py').read_text()
assert not re.search(r'private\s+(?:val|var)\s+(?:world|player|mc|network|interaction|gpuDevice|inGame)\s*[:=]',m)
assert len(re.findall(r'by (?:enumChoice|float|int|text|boolean)\(',m))==9
for lang in ['en_us','zh_cn','zh_cn_ui']:
 duplicates=[]
 def pairs(pairs):
  d={}
  for a,b in pairs:
   if a in d:duplicates.append(a)
   d[a]=b
  return d
 d=json.loads((root/f'src/main/resources/resources/liquidbounce/lang/{lang}.json').read_text(),object_pairs_hook=pairs)
 assert not duplicates,duplicates
 if lang=='zh_cn_ui':assert d['modules']['CS2MVP']=='CS2 MVP横幅'
 else:assert 'liquidbounce.module.cS2MVP.description' in d
print('PASS: 9 live settings, module registration, pinned layout/colour values, death-only/thread/epoch/friend/team/HUD guards and JSON uniqueness wiring checks')
