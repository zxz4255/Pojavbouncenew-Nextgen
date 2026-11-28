#!/usr/bin/env python3
"""Compare every functional setting with pinned upstream; descriptions are not dummy options."""
from pathlib import Path
import json,re,hashlib,sys
root=Path(__file__).resolve().parents[2]
up=root/'licenses/neoraven/combat-upstream'
lock=json.loads((Path(__file__).parent/'source-lock.json').read_text())
for name,digest in lock['upstream'].items():
 assert hashlib.sha256((up/name).read_bytes()).hexdigest()==digest,name
port=(root/'src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/combat/neoraven/NeoAuraSettings.kt').read_text()
def calls(text):
 result={}
 for kind,args in re.findall(r'^\s*(flag|slider|mode|tint)\((.*?)\)\s*$',text,re.M):
  name=json.loads(re.match(r'"(?:[^"\\]|\\.)*"',args)[0])
  result[name]=(kind,args)
 return result
count=0
original=json.loads((up/'KillAura-settings.json').read_text())
for row in original['settings']:
 if row['t']=='desc':continue
 section=port.split('class NeoOriginalSettings',1)[1].split('class NeoLeaderSettings',1)[0]
 rows=calls(section);kind,args=rows[row['name']]
 if row['t']=='toggle':assert kind=='flag' and args.endswith(str(row['value']).lower()),row
 elif row.get('options'):
  assert kind=='mode'
  assert re.findall(r'"([^"]*)"',args)[1:]==row['options'],row
  assert int(args.split(',')[1])==int(row['value']),row
 else:
  nums=[float(v.strip().removesuffix('f')) for v in args.split(',')[1:]]
  assert nums==[row[k] for k in ('value','min','max','step')],row
 count+=1
src=(up/'KillAura2Module.java.txt').read_text()
for cls,prefix in [('NeoLeaderSettings','leader'),('NeoExpoSettings','')]:
 section=port.split('class '+cls,1)[1].split('\nclass ',1)[0];rows=calls(section)
 pattern=r'private final (SliderSetting|ButtonSetting|ColorSetting) \w+ = ('+(prefix+r'Slider|'+prefix+r'Mode|'+prefix+r'Flag' if prefix else r'slider|mode|flag|color')+r')\((.*?)\);'
 for typ,func,args in re.findall(pattern,src,re.S):
  args=' '.join(args.split());name=json.loads(re.match(r'"[^"]*"',args)[0])
  kind,p=rows[name]
  if func.lower().endswith('slider'):
   assert kind=='slider'
   assert [float(n.removesuffix('f')) for n in p.split(', ')[1:]]==[float(n) for n in args.split(', ')[1:5]],name
  elif func.lower().endswith('mode'):
   assert kind=='mode' and p==args,(name,p,args)
  elif func.lower().endswith('flag'):assert kind=='flag' and p==args,name
  else:assert kind=='tint' and args.replace('0xFFFF5555','0xFFFF5555.toInt()').replace('0xFFFF2020','0xFFFF2020.toInt()')==p,name
  count+=1
section=port.split('class NeoFluxSettings',1)[1];rows=calls(section)
for func,args in re.findall(r'(?:SliderSetting|ButtonSetting) \w+ = (mode|slider|flag)\((.*?)\);',(up/'KillAura2Flux.java.txt').read_text()):
 name=json.loads(re.match(r'"[^"]*"',args)[0]);kind,p=rows[name]
 if func=='slider':assert [float(n.removesuffix('f')) for n in p.split(', ')[1:]]==[float(n) for n in args.split(', ')[1:5]],name
 elif func=='mode':
  idx=args.index(',');assert p==args[:idx]+', 0'+args[idx:],name
 else:assert p==args,name
 count+=1
modules=root/'src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/combat/neoraven'
a2=(modules/'ModuleNeoKillAura2.kt').read_text()
assert 'boolean("Pause While Blocking",true)' in a2
assert 'enumChoice("Client", Client.LEADER)' in a2
assert 'EXPO("Expo"), LEADER("Leader"), FLUX("Flux")' in a2
# Each functional option is read by code, not just displayed.
combined='\n'.join(p.read_text() for p in modules.glob('*.kt') if p.name!='NeoAuraSettings.kt')
for name in set(re.findall(r'^\s*(?:flag|slider|mode|tint)\("([^"]+)"',port,re.M)):
 assert '"'+name+'"' in combined, 'unread setting: '+name
support=(modules/'NeoAuraSupport.kt').read_text()
combat=(root/'src/main/kotlin/net/ccbluex/liquidbounce/utils/combat/CombatManager.kt').read_text()
assert 'CombatManager.shouldPauseCombat' in support and 'val shouldPauseCombat: Boolean' in combat
assert 'ignoreCondition' not in support
assert '"Aura"' in support # Ts2's real registered name, not its alias
manager=(root/'src/main/kotlin/net/ccbluex/liquidbounce/features/module/ModuleManager.kt').read_text()
for name in ['ModuleNeoKillAura','ModuleNeoKillAura2']:
 assert manager.count('            '+name+',')==1,name
sword=(root/'src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/combat/ModuleSwordBlock.kt').read_text()
assert sword.count('ModuleNeoKillAura.shouldAnimateSwordBlock')==3
print(f'PASS: pinned upstream hashes; {count+2} functional settings incl. Client/shared pause; exact labels/defaults/ranges/steps/choices; no unread options; both registrations and 3 visual-block gates')
