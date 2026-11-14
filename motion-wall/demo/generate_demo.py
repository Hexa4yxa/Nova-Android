#!/usr/bin/env python3
"""Generate an ORIGINAL free stylized driving-loop sample for Android tests.

This is NOT AI photo-to-video. All pixels are procedural and have no external
licensing, stock footage, network use, or proprietary media requirements.
Requires pillow and ffmpeg; CI generates the actual MP4 as an APK asset.
"""
from pathlib import Path
from math import sin, cos, pi
from PIL import Image, ImageDraw
import tempfile, subprocess, sys

W,H,FPS,N=432,936,20,120
target=Path(sys.argv[1] if len(sys.argv)>1 else "app/src/main/assets/drive_demo.mp4")
target.parent.mkdir(parents=True,exist_ok=True)
with tempfile.TemporaryDirectory() as td:
 for f in range(N):
  t=f/N
  img=Image.new("RGB",(W,H),(7,11,25)); d=ImageDraw.Draw(img)
  for y in range(H):
   k=y/H
   d.line((0,y,W,y),fill=(int(8+16*k),int(12+15*k),int(28+24*k)))
  # Deterministic scrolling backdrop: at t=1 exactly one image-width elapsed.
  for repeat in (-1,0,1,2):
   for j in range(-2,17):
    x=j*49; top=603-(70+(j*73)%175); bw=32+(j*13)%25
    xx=int(x-W*t+repeat*W)
    if xx>W or xx+bw<0:continue
    d.rectangle((xx,top,xx+bw,635),fill=(15+j%4*2,21+j%5*2,40+j%3*4))
    for wy in range(top+11,615,14):
     for wx in range(xx+6,xx+bw-3,11):
      if ((wx*7+wy*3+j*11)%19)<7:
       d.rectangle((wx,wy,wx+3,wy+5),fill=(170,110,55) if j%3 else (59,105,155))
  d.rectangle((0,635,W,725),fill=(17,28,44))
  d.polygon(((0,726),(W,714),(W,H),(0,H)),fill=(16,23,35))
  # Road marks move left, completing an exact number of cycles in 6 seconds.
  shift=(840*t)%84
  for k in range(-2,10):
   sx=k*84-shift
   d.polygon(((sx,842),(sx+47,840),(sx+59,847),(sx+8,850)),fill=(192,179,117))
  carx=W*.5-169+4*sin(2*pi*t)
  y=760+3*sin(4*pi*t)
  d.ellipse((carx+23,y-14,carx+335,y+28),fill=(5,8,17))
  # Distinct sports coupe body + windows.
  d.polygon(((carx+5,y-60),(carx+18,y-88),(carx+72,y-107),(carx+128,y-153),
     (carx+240,y-155),(carx+281,y-112),(carx+317,y-99),(carx+333,y-70),
     (carx+331,y-42),(carx+310,y-31),(carx+18,y-31)),fill=(12,18,32))
  d.polygon(((carx+73,y-105),(carx+129,y-148),(carx+238,y-149),(carx+275,y-108)),
      fill=(33,48,78))
  d.polygon(((carx+133,y-143),(carx+181,y-144),(carx+180,y-110),(carx+91,y-110)),
      fill=(33,64,86))
  d.polygon(((carx+191,y-144),(carx+237,y-144),(carx+268,y-110),(carx+189,y-110)),
      fill=(23,47,72))
  d.line((carx+184,y-146,carx+183,y-106),fill=(9,13,23),width=5)
  d.line((carx+26,y-96,carx+297,y-93),fill=(92,120,170),width=3)
  d.line((carx+282,y-84,carx+329,y-81),fill=(219,244,255),width=6)
  d.line((carx+8,y-72,carx+26,y-73),fill=(245,48,68),width=5)
  # The wheels really rotate, not simply the image moving in place.
  for wheelx in (carx+89,carx+266):
   cy=y-27;r=34
   d.ellipse((wheelx-r-2,cy-r-2,wheelx+r+2,cy+r+2),fill=(5,7,11))
   d.ellipse((wheelx-r+7,cy-r+7,wheelx+r-7,cy+r-7),fill=(53,70,93))
   d.ellipse((wheelx-r+11,cy-r+11,wheelx+r-11,cy+r-11),fill=(17,23,36))
   for spoke in range(6):
    a=16*pi*t+spoke*pi/3
    d.line((wheelx+9*cos(a),cy+9*sin(a),wheelx+24*cos(a),cy+24*sin(a)),
        fill=(172,182,198),width=4)
   d.ellipse((wheelx-5,cy-5,wheelx+5,cy+5),fill=(121,140,173))
  d.line((carx+20,y-33,carx+313,y-33),fill=(30,55,95),width=3)
  d.text((22,45),"DRIVE LOOP  //  FREE DEMO",fill=(121,149,206))
  d.text((22,65),"ACTUAL ANIMATION  |  OFFLINE",fill=(84,113,160))
  img.save(Path(td)/("f%03d.png"%f),compress_level=1)
 subprocess.run(["ffmpeg","-y","-hide_banner","-loglevel","error",
     "-framerate",str(FPS),"-i",str(Path(td)/"f%03d.png"),
     "-c:v","libx264","-preset","fast","-crf","24","-pix_fmt","yuv420p",
     "-movflags","+faststart",str(target)],check=True)
print("Generated",target,target.stat().st_size,"bytes")
