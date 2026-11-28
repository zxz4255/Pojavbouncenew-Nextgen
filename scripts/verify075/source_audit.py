#!/usr/bin/env python3
"""Pinned upstream, source-preservation, defaults and static integration audit. Not runtime proof."""
from pathlib import Path
import json, hashlib, re, os, subprocess
HERE=Path(__file__).parent
ROOT=HERE.resolve().parents[1]
K=ROOT/'src/main/kotlin/net/ccbluex/liquidbounce'
N=K/'features/module/modules/player/neoraven'
manifest=json.loads((HERE/'upstream/manifest.json').read_text())
for path,digest in manifest['sha256'].items():
    assert hashlib.sha256((HERE/'upstream'/Path(path).name).read_bytes()).hexdigest()==digest,path
assert hashlib.sha256((HERE/'upstream/settings.json').read_bytes()).hexdigest()==manifest['settings_sha256']
protected=json.loads((HERE/'protected-files.json').read_text())
for path,digest in protected.items():assert hashlib.sha256((ROOT/path).read_bytes()).hexdigest()==digest,path
assert (ROOT/'licenses/neoraven/CHESTS.md').is_file()
print(f'PASS: 12 pinned upstream source hashes/settings; {len(protected)} old ChestAura/Stealer, FPSKnife and 074 effect files byte-identical to delivered074.')

settings=json.loads((HERE/'upstream/settings.json').read_text())
aura=(N/'ModuleNeoChestAura.kt').read_text();stealer=(N/'ModuleNeoChestStealer.kt').read_text()
for setting in settings:
    source=aura if setting['name']=='ChestAura' else stealer
    count=0
    for value in setting['settings']:
        kind=value['t']
        if kind=='desc':continue
        count+=1
        name=value['name'].title().replace(' ','').replace('-','')
        if kind=='toggle':
            expected=f'boolean("{name}",{str(value["value"]).lower()})'
            assert expected in source,(setting['name'],expected)
        elif name=='MoveFix':assert 'enumChoice("MoveFix",MoveFix.SILENT)' in source
        elif name in ['Range','PlayerCheckRange']:
            assert f'float("{name}",{int(value["value"])}f,0f..{int(value["max"])}f' in source
            assert '(it*10f).roundToInt()/10f' in source
        else:
            assert f'int("{name}",{int(value["value"])},0..1000,"ms").onChange {{ (it/10f).roundToInt()*10 }}' in source
    assert count==(5 if setting['name']=='ChestAura' else 15)
assert 'ticksUntilReset = 1' in aura and 'Priority.NOT_IMPORTANT' in aura
assert 'ModuleChestAura.running' in aura and 'ModuleChestStealer.running' in aura
assert 'running && !ModuleChestStealer.running' in stealer
assert 'handleContainerInput(menu.containerId,slot,0,ContainerInput.QUICK_MOVE,player)' in stealer
assert 'InventoryManager' not in stealer # no old queue/filters/actions delegated
print('PASS: original 5 Aura / 15 Stealer settings, defaults, steps, separate queues/config names, low-priority rotations and one-sided old-module conflict guards.')

registry=(K/'features/module/ModuleManager.kt').read_text()
for name in ['ModuleNeoChestAura','ModuleNeoChestStealer','ModuleChestAura','ModuleChestStealer','ModuleCinematic','ModuleRainReflections']:
    assert registry.count('            '+name+',')==1,name
mixins=json.loads((ROOT/'src/main/resources/liquidbounce.mixins.json').read_text())['client']
for name in ['MixinNeoChestPackets','MixinNeoChestAcceptedSend']:assert mixins.count('minecraft.network.'+name)==1
java=ROOT/'src/main/java/net/ccbluex/liquidbounce/injection/mixins/minecraft/network'
packet=(java/'MixinNeoChestPackets.java').read_text();send=(java/'MixinNeoChestAcceptedSend.java').read_text()
assert 'at = @At("TAIL")' in packet and 'at = @At("RETURN")' in send
callbacks=(N/'NeoChestEnvironment.kt').read_text()
assert callbacks.index('ModuleNeoChestAura.contentsReceived(id)') < callbacks.index('ModuleNeoChestStealer.contentsReceived(id)')
assert not re.search(r'putStackInSlot|setItem\(|setRemoteSlot|predictionChanged',callbacks+stealer)
if os.environ.get('JAVA_HOME') and os.environ.get('MC_JAR'):
    output=subprocess.check_output([os.environ['JAVA_HOME']+'/bin/javap','-s','-classpath',os.environ['MC_JAR'],'net.minecraft.client.multiplayer.ClientPacketListener'],text=True)
    for name in re.findall(r'@Inject\(method = "([^"]+)"',packet):assert 'void '+name+'(' in output,name
    sendApi=subprocess.check_output([os.environ['JAVA_HOME']+'/bin/javap','-s','-classpath',os.environ['MC_JAR'],'net.minecraft.network.Connection'],text=True)
    assert '(Lnet/minecraft/network/protocol/Packet;)V' in sendApi
hud=(K/'features/module/modules/render/OpaiChestLoot.kt').read_text()
assert 'ModuleChestStealer.run { screen.canBeStolen() }' in hud
assert 'ModuleNeoChestStealer.canLootScreen(screen)' in hud
assert '?.let(ModuleNeoChestStealer::prepareVisual)' in hud
print('PASS: both old and new modules registered exactly once; additive HUD bridge; native packet target API/order audit. No Mixin application or cancellation-order runtime claim.')

for lang in ['en_us','zh_cn','zh_cn_ui']:
    def unique(pairs):
        d={}
        for key,value in pairs:
            assert key not in d,(lang,key)
            d[key]=value
        return d
    d=json.loads((ROOT/f'src/main/resources/resources/liquidbounce/lang/{lang}.json').read_text(),object_pairs_hook=unique)
    if lang=='zh_cn_ui':assert d['modules']['NeoChestAura']=='Neo自动开箱' and d['modules']['NeoChestStealer']=='Neo自动拿箱'
    else:assert 'liquidbounce.module.neoChestStealer.description' in d
print('PASS: EN/ZH descriptions and independent native Chinese UI labels; no duplicate JSON keys.')
