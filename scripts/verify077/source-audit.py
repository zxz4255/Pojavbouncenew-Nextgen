#!/usr/bin/env python3
"""Byte-isolation against immutable delivered076; not a game regression test."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
BASE = json.loads((HERE / 'baseline076.sha256.json').read_text())
RIG = 'src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/render/fpsknife/'
ALLOWED_CHANGES = {RIG + f for f in (
    'FpsKnifeAnimator.kt', 'FpsKnifeMeshes.kt', 'FpsKnifeRig.kt', 'ModuleFpsKnife.kt'
)}
ALLOWED_ADDITIONS = {
    RIG + 'FpsKnifeChoreography.kt',
    '077-FPS-KNIFE-REWORK.md',
    'references/cs2-077/REFERENCES.md',
    *(f'scripts/verify077/{name}' for name in (
        'run.sh', 'ChoreographyRegression.kt', 'README.md', 'results.txt',
        'source-audit.py', 'baseline076.sha256.json', 'source-audit-results.txt',
    )),
}
EXCLUDED = {'.git', '.gradle', '.kotlin', 'build', 'node_modules', '__pycache__', '.idea'}
changed = []
for name, expected in BASE.items():
    path = ROOT / name
    assert path.is_file(), f'Deleted baseline file: {name}'
    actual = hashlib.sha256(path.read_bytes()).hexdigest()
    if actual != expected:
        assert name in ALLOWED_CHANGES, f'Unexpected baseline mutation: {name}'
        changed.append(name)
assert set(changed) == ALLOWED_CHANGES, f'Unexpected changed file set: {changed}'
added = []
for path in ROOT.rglob('*'):
    if not path.is_file() or any(part in EXCLUDED for part in path.relative_to(ROOT).parts):
        continue
    name = path.relative_to(ROOT).as_posix()
    if name not in BASE:
        assert name in ALLOWED_ADDITIONS, f'Unlisted addition: {name}'
        added.append(name)
print(f'PASS: {len(BASE)} baseline files checked; {len(changed)} planned knife modifications; {len(BASE)-len(changed)} existing files byte-identical.')
print(f'PASS: {len(added)} explicitly allowed additions; no baseline deletions.')
print('Modified:\n' + '\n'.join(sorted(changed)))
print('Added:\n' + '\n'.join(sorted(added)))
