#!/usr/bin/env python3
"""Static safety/default/provenance audit. Does not prove real packet acceptance or Mixin linkage."""
from pathlib import Path
import json, hashlib, re
HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[1]
K=ROOT/'src/main/kotlin/net/ccbluex/liquidbounce'
N=K/'features/module/modules/player/neoraven'
manifest=json.loads((HERE/'upstream/manifest.json').read_text())
for name,digest in manifest['sha256'].items():assert hashlib.sha256((HERE/'upstream'/name).read_bytes()).hexdigest()==digest,name
for path,digest in json.loads((HERE/'protected-files.json').read_text()).items():assert hashlib.sha256((ROOT/path).read_bytes()).hexdigest()==digest,path
assert (ROOT/'licenses/neoraven/INVENTORY.md').is_file()
print('PASS: pinned upstream InvManager2/ItemUtils/BlockUtils/settings hashes, attribution and protected 075 chest/knife/effect/layout/inventory-infrastructure source hashes.')

neo=(N/'ModuleNeoInvManager.kt').read_text();ts=(K/'features/module/modules/player/ModuleInvManager.kt').read_text()
session=(K/'utils/inventory/SafeInventorySession.kt').read_text();policy=(K/'utils/inventory/InventorySafetyPolicy.kt').read_text()
settings=json.loads((HERE/'upstream/settings.json').read_text())[0]['settings'];count=0
for item in settings:
 if item['t']=='desc':continue
 count+=1
 name=item['name'].title().replace(' ','')
 if item['t']=='toggle':assert f'boolean("{name}",{str(item["value"]).lower()})' in neo,name
 else:
  lo=int(item['min']);hi=int(item['max']);default=int(item['value'])
  assert f'int("{name}",{default},{lo}..{hi}' in neo,name
assert count==28
for text in [neo,ts]:
 assert 'items("DiscardItems", itemSortedSetOf())' in text
 assert 'SafeInventorySession.mayDiscard(' in text
 assert 'Paused: reopen inventory' in text
 assert 'handleContainerInput' not in text
assert 'ModuleNeoInvManager.running || ModuleInventoryCleaner.running || ModuleAutoArmor.running' in ts
assert 'ModuleInventoryCleaner.running || ModuleAutoArmor.running' in neo
assert 'boolean("Drop", false)' in ts
assert 'boolean("RepairEquipment", false).visibleWhen { false }' in ts
for banned in ['ContainerInput.THROW','ContainerInput.PICKUP_ALL','ServerboundPlayerCommandPacket','handleSaveArmorChestSteal','slotSettings[otherIdx].set','System.identityHashCode']:
 assert banned not in ts,banned
assert 'ItemStack.matches(prev, st)' in ts and 'inventoryScreen !== inventoryScreenIdentity' in ts
assert 'stepSortHotbar' in ts and 'InventorySafetyPolicy.nextSwap' in ts
assert 'val incoming' in session and session.count('gameMode.handleContainerInput(')==1
assert session.index('incoming.count > source.getMaxStackSize(incoming)')<session.index('gameMode.handleContainerInput(')
assert 'original.count > target.getMaxStackSize(original)' in session
assert 'current.menu.slots.size != 46' in session and 'player.containerMenu !== player.inventoryMenu' in session
assert 'ItemStack.isSameItemSameComponents(it, stack)' in session
assert 'discardAllowed(stack.item in allow' in session
assert 'maxOf(250L, delay)' in policy and 'tick != lastCheckTick' in policy and '!visited.add(before)' in policy
assert 'id !in 9..44 || source == null || click.button != 1' in policy
assert 'if (id !in 5..44)' in policy
assert not re.search(r'\.setItem\(|\.setCarried\(|\.setRemoteSlot\(',session+neo+ts)
registry=(K/'features/module/ModuleManager.kt').read_text()
assert registry.count('            ModuleNeoInvManager,')==1 and registry.count('            ModuleInvManager,')==1
print('PASS: all 28 upstream settings/defaults + new empty discard lists; TS/Neo independent registration/interlock; single native click site, exact component IDs, capacity preflight, 250ms/tick gate and unsafe TS paths removed (static audit).')

assert not (K/'ui/clickgui/neumorph/ClickGuiNeumorphSurface.kt').exists()
for p in (K/'ui').rglob('*.kt'):
 text=p.read_text()
 for banned in ['ClickGuiNeumorphSurface','neumorphMode','NeumorphTone','neumorphDepth']:
  assert banned not in text,(p,banned)
style=(K/'features/module/modules/render/ModuleClickGui.kt').read_text()
assert 'NEUMORPHISM(' not in style and 'NeumorphTone' not in style and 'NeumorphDepth' not in style
assert 'listOf("Neumorphism")' in style and 'MODERN("Modern")' in style and 'GLASS("Glass")' in style
for language in ['en_us','zh_cn','zh_cn_ui']:
 def unique(pairs):
  out={}
  for key,value in pairs:
   assert key not in out,(language,key)
   out[key]=value
  return out
 data=json.loads((ROOT/f'src/main/resources/resources/liquidbounce/lang/{language}.json').read_text(),object_pairs_hook=unique)
 if language=='zh_cn_ui':
  assert data['modules']['NeoInvManager']=='Neo背包整理'
  assert not any('neumorph' in key.lower() for key in data['terms'])
 else:
  assert 'liquidbounce.module.neoInvManager.discardItems.description' in data
  assert 'liquidbounce.module.invManager.discardItems.description' in data
print('PASS: Neumorphism renderer/settings/UI branches/labels removed; Modern/Glass remain, old style alias maps to Modern; EN/ZH JSON contains descriptions without duplicate keys.')
