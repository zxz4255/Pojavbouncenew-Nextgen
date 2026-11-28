#!/usr/bin/env python3
"""Reproduce source scanline samples (requires user's ORIGINAL JPEGs, numpy/scipy/Pillow).
Not independent validation: these traces were also used when fitting the material.
"""
from pathlib import Path
import sys,json,hashlib
import numpy as np
from PIL import Image
from scipy.ndimage import gaussian_filter1d
R=Path(__file__).resolve().parents[2];U=Path(sys.argv[1]) if len(sys.argv)>1 else R.parent/'uploads'
spec=json.loads((R/'licenses/glass089/reference-spec.json').read_text())
for item in spec['source_images']:assert hashlib.sha256((U/item['file']).read_bytes()).hexdigest()==item['sha256']
for key,trace,sigma,window in [('target','target_fold_trace',1.8,(.62,.84)),('counter','counter_seam_trace',.65,(.42,.51))]:
 reg=spec['regions'][key];a=np.asarray(Image.open(U/reg['file']).crop(reg['box'])).mean(2);h,w=a.shape
 for y,expected in spec[trace]:
  row=gaussian_filter1d(a[round(y*(h-1))],sigma)
  if key=='counter':row-=gaussian_filter1d(row,5)
  lo,hi=int(w*window[0]),int(w*window[1]);found=lo+int(row[lo:hi].argmax())
  assert abs(found-expected*w)<1e-8
  print(key,'source_y=',y,'source_x=',found,'/',w)
print('PASS:13 traced source-coordinate samples reproduced from locked original JPEGs; NOT full-image parity')
