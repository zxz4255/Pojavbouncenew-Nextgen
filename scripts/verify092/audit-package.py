#!/usr/bin/env python3
"""Strict source-only diagnostic scope, not a runtime acceptance test."""
from pathlib import Path
import zipfile,hashlib,json,sys
R=Path(__file__).resolve().parents[2];B=Path(sys.argv[1]);Z=Path(sys.argv[2]);O=R/'scripts/verify092'
K='src/main/kotlin/net/ccbluex/liquidbounce/'
expected='280aa8b93086d35c531a9303849153d04706b231cd531f76e4ca07ba9e403245'
assert hashlib.sha256(B.read_bytes()).hexdigest()==expected
modified={K+s for s in ['config/types/Value.kt','features/module/modules/player/neoraven/ModuleNeoInvManager.kt','features/module/modules/player/neoraven/NeoInvHotkeys.kt']}
newproduction={K+s for s in ['config/types/ValueWriteTrace.kt','features/module/modules/player/neoraven/NeoInvDiagnostics.kt','features/module/modules/player/neoraven/NeoInvTraceWriter.kt']}
files={p.relative_to(R).as_posix():p for p in R.rglob('*') if p.is_file() and not any(x in p.relative_to(R).parts for x in ['.git','.gradle','.cache','__pycache__','build','node_modules'])}
with zipfile.ZipFile(B) as b:
 base=set(b.namelist());assert len(base)==2763
 assert not base-files.keys()
 changes={p for p in base if files[p].read_bytes()!=b.read(p)}
 assert changes==modified,(changes,modified)
 added=set(files)-base
 assert {p for p in added if p.startswith('src/')}==newproduction
 assert all(p in newproduction or p.startswith('scripts/verify092/') or p=='092-NEOINV-DIAGNOSTIC.md' for p in added)
 value=(R/(K+'config/types/Value.kt')).read_text()
 assert 'private var writeTrace: Consumer<ValueWriteTrace<T>>? = null' in value
 assert 'traceWrite(id, ValueWriteTrace.Stage.REJECTED' in value
 assert 'traceWrite(id, ValueWriteTrace.Stage.OBSERVER_FAILED' in value
 installs=[]
 for p in (R/'src').rglob('*'):
  if p.is_file() and p.suffix in ['.kt','.java'] and '.traceWrites' in p.read_text():installs.append(p.relative_to(R).as_posix())
 assert installs==[K+'features/module/modules/player/neoraven/NeoInvDiagnostics.kt'],installs
 neo=(R/(K+'features/module/modules/player/neoraven/ModuleNeoInvManager.kt')).read_text()
 assert 'NeoInvDiagnostics.install(enabledValue)' in neo and '092-DIAGNOSTIC' in neo
 assert 'enabled = true' not in neo
 for p in newproduction:
  assert '.enabled =' not in files[p].read_text() and '.set(true)' not in files[p].read_text(),p
 result=(O/'lifecycle-results.txt').read_text()
 assert 'PASS: 6148 assertions.' in result and ': error:' not in result
 report={'status':'PASS','deliverable':'DIAGNOSTIC SOURCE, NOT CONFIRMED FIX OR MOD JAR','baselineSHA256':expected,
  'modified':sorted(changes),'newProduction':sorted(newproduction),'baselineIdentical':len(base)-len(changes),
  'deleted':[],'proof':'complete Value/ToggleableValueGroup/ClientModule/Neo setter-chain bounded test + diagnostics output tests; no game/root-cause claim'}
 (O/'scope-audit.json').write_text(json.dumps(report,indent=2)+'\n');files['scripts/verify092/scope-audit.json']=O/'scope-audit.json'
 with zipfile.ZipFile(Z,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
  for path,p in sorted(files.items()):z.writestr(path,p.read_bytes())
 with zipfile.ZipFile(Z) as z:
  assert z.testzip() is None and len(z.namelist())==len(files)
  for path,p in files.items():assert z.read(path)==p.read_bytes(),path
  for path in base-modified:assert z.read(path)==b.read(path),path
 digest=hashlib.sha256(Z.read_bytes()).hexdigest();Z.with_suffix(Z.suffix+'.sha256').write_text(digest+'  '+Z.name+'\n')
 print(json.dumps({'status':'PASS','entries':len(files),'bytes':Z.stat().st_size,'modified':len(changes),
   'unchanged':len(base)-len(changes),'added':len(files)-len(base),'deleted':0,'SHA256':digest},indent=2))
