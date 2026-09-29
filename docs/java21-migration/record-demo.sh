#!/bin/bash
# Records the EGM2008Offsets demo on an Xvfb display. Requires Xvfb, openbox, xterm, wmctrl, ffmpeg, ImageMagick,
# a completed `ant -f release-build.xml` and config/egm2008_25.dat.
cd "$(dirname "$0")/../.."
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64} PATH=$JAVA_HOME/bin:$PATH
mkdir -p build/demo
export DISPLAY=:99
Xvfb :99 -screen 0 1600x900x24 +extension GLX &
XVFB=$!; sleep 2
openbox & sleep 1
ffmpeg -y -loglevel error -f x11grab -video_size 1600x900 -framerate 15 -i :99 -c:v libx264 -preset veryfast -pix_fmt yuv420p build/demo/EGM2008Offsets-jdk21.mp4 &
FF=$!; sleep 1
xterm -geometry 90x50+0+0 -fa Monospace -fs 10 -T "java -version && ./run-demo.bash EGM2008Offsets" -e bash -c 'java -version; echo; ./run-demo.bash gov.nasa.worldwindx.examples.EGM2008Offsets 2>&1 | tee build/demo/EGM2008Offsets-jdk21.log' &
sleep 8
wmctrl -l
WID=$(wmctrl -l | grep -i 'world' | awk '{print $1}' | head -1)
[ -n "$WID" ] && wmctrl -i -r $WID -e 0,640,0,960,900
sleep 50
import -window root build/demo/EGM2008Offsets-jdk21.png
kill -INT $FF; sleep 3
pkill -f 'EGM2008Offsets'; pkill xterm; pkill openbox; kill $XVFB
