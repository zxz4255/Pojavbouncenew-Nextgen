#!/usr/bin/env python3
"""Strengthen 085 compile hosts without rewriting historical verification fixtures."""
from pathlib import Path
import sys
out=Path(sys.argv[1])
p=out/'ClientModule.kt';s=p.read_text();old=':ValueGroup(n),EventListener {';assert old in s
p.write_text(s.replace(old,':ValueGroup(n),EventListener,MinecraftShortcuts {'))
p=out/'Client.kt';s=p.read_text();p.write_text(s+'\nval inGame:Boolean get()=mc.player!=null && mc.level!=null\n')
print('087 host now inherits the ACTUAL production MinecraftShortcuts; only global inGame facade is a project host')
