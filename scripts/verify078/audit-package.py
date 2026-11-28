#!/usr/bin/env python3
"""Strict 077 -> 078 scope/integrity check and flat source archive."""
import hashlib, json, pathlib, sys, zipfile
root=pathlib.Path(__file__).resolve().parents[2]
baseline=pathlib.Path(sys.argv[1]); dest=pathlib.Path(sys.argv[2])
assert hashlib.sha256(baseline.read_bytes()).hexdigest()=='9268ffa12a15957b8f61e3a5e6e46463b316bd25f5173fbe8cf2dcc2e2a9261f', 'Wrong 077 baseline'
r='src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/misc/music/'
allowed={r+n for n in ['KugouClient.kt','ModuleMusic.kt','MusicIsland.kt','MusicManager.kt','MusicScreen.kt','onyx/hud/Island.kt']}
allowed.add('src/main/java/net/ccbluex/liquidbounce/utils/audio/parser/MusicAudioParserFactory.java')
audit='scripts/verify078/scope-audit.json'
sha=lambda data:hashlib.sha256(data).hexdigest()
with zipfile.ZipFile(baseline) as old:
    names={n for n in old.namelist() if not n.endswith('/')}
    changed=[]; identical=[]
    for n in sorted(names):
        p=root/n
        assert p.is_file(), 'Missing '+n
        if p.read_bytes()!=old.read(n):
            assert n in allowed, 'Out-of-scope change '+n
            changed.append({'path':n,'before':sha(old.read(n)),'after':sha(p.read_bytes())})
        else: identical.append(n)
    assert {c['path'] for c in changed}==allowed, 'Unexpected change set'
    additions={'078-MUSIC-FIXES.md',r+'KugouPlayback.kt'}
    for directory in ['scripts/verify078','references/music078']:
        additions.update(str(p.relative_to(root)) for p in (root/directory).rglob('*') if p.is_file() and '__pycache__' not in p.parts)
    additions.add(audit)
    assert not (additions & names)
    report={'baseline':'077','baseline_sha256':sha(baseline.read_bytes()),'baseline_files':len(names),'changed':changed,'identical_count':len(identical),'deleted':[], 'added':sorted(additions),'packaged_count':len(names|additions),'protected_nonmusic_baseline':'Every baseline file outside seven explicitly listed music changes is byte-identical, including knife/inventory/chest/ClickGUI and logs.'}
    (root/audit).write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    with zipfile.ZipFile(dest,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as archive:
        for n in sorted(names|additions): archive.write(root/n,n)
with zipfile.ZipFile(dest) as check:
    assert check.testzip() is None
    assert set(check.namelist())==names|additions
    for n in check.namelist(): assert check.read(n)==(root/n).read_bytes(), n
raw=dest.read_bytes()
blocks=b''.join(hashlib.sha256(raw[i:i+4194304]).digest() for i in range(0,len(raw),4194304))
result={'file':str(dest),'baseline':'077','files':len(names|additions),'size':len(raw),'sha256':sha(raw),'md5':hashlib.md5(raw).hexdigest(),'dropbox_content_hash':sha(blocks),'tests':'24 protocol + 18 manager/parser scenarios + 8 Island combinations/80 disabled cycles PASS; screen/module source assertions PASS; focused compilation with host/native stubs, no full Gradle/game/live VIP verification','changed_baseline_files':len(changed),'identical_baseline_files':len(identical),'additions':len(additions),'zip_integrity':'PASS','flat_root':True}
dest.with_suffix(dest.suffix+'.sha256').write_text(result['sha256']+'  '+dest.name+'\n')
pathlib.Path('/home/user/078-delivery.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(result,ensure_ascii=False,indent=2))
