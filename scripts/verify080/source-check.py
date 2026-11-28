#!/usr/bin/env python3
"""Minimal patch assertions and temporary, namespace/inheritance-accurate host fixtures."""
import pathlib,re,shutil,sys,zipfile,hashlib
root=pathlib.Path(__file__).resolve().parents[2];work=pathlib.Path(sys.argv[1]);baseline=pathlib.Path(sys.argv[2])
k=root/'src/main/kotlin/net/ccbluex/liquidbounce';n=k/'features/module/modules/player/neoraven'
assert hashlib.sha256(baseline.read_bytes()).hexdigest()=='49433d5437a97e094852f64a8167cab37f6d258d2876fe250437e239695ac1d0'
with zipfile.ZipFile(baseline) as z:
    for name in ['ModuleNeoChestAura','ModuleNeoChestStealer','ModuleNeoInvManager']:
        path=n/(name+'.kt');rel=str(path.relative_to(root));original=z.read(rel).decode();current=path.read_text()
        if name=='ModuleNeoChestAura':
            expected=original.replace('import net.ccbluex.liquidbounce.event.events.*','import net.ccbluex.liquidbounce.event.EventState\nimport net.ccbluex.liquidbounce.event.events.*',1)
        else:
            # Rename identifiers only, never Minecraft package segments in imports.
            lines=original.splitlines(keepends=True)
            expected=''.join(line if line.startswith('import ') else re.sub(r'\bworld\b','sessionWorld',line) for line in lines)
            assert 'private var world:' not in current and 'override var world' not in current
        assert current==expected,rel
        out=work/'original'/path.name;out.parent.mkdir(exist_ok=True);out.write_text(original)
# Extract actual enum declaration and actual parent package, not the former events.* stand-in.
event=(k/'event/Event.kt').read_text();enum=re.search(r'enum class EventState\([^)]*\)\s*\{[^}]*\}',event,re.S).group()
(work/'EventState.kt').write_text('package net.ccbluex.liquidbounce.event\n'+enum+'\n')
for version,dest in [('075','chest-stubs'),('076','inv-stubs')]:
    d=work/dest;shutil.copytree(root/f'scripts/verify{version}/stubs',d)
    if version=='075':(d/'Visual.kt').unlink()
    p=d/'Events.kt';s=p.read_text().replace('enum class EventState { PRE, POST }\n','')
    s=s.replace('import net.ccbluex.liquidbounce.event.Event','import net.ccbluex.liquidbounce.event.EventState\nimport net.ccbluex.liquidbounce.event.Event',1);p.write_text(s)
    p=d/'S6.kt';s=p.read_text().replace(': ValueGroup(name, aliases), Listenable {',': ValueGroup(name, aliases), Listenable, MinecraftShortcuts {')
    s=s.replace('    protected val mc get() = net.minecraft.client.Minecraft.getInstance()\n','');p.write_text(s)
    p=d/'Client.kt';s=p.read_text()
    if 'val inGame' not in s:s+='\nval inGame get() = true\n'
    p.write_text(s)
print('PASS: production diff is exactly one EventState import and two private world -> sessionWorld identifier renames; no logic/settings changes.')
print('PASS: temporary fixtures now inherit the ACTUAL MinecraftShortcuts and resolve EventState from its ACTUAL parent package; historical075/076 fixtures remain unchanged.')
