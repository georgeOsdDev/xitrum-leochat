#!/bin/sh
# You may need to customize memory config below to optimize for your environment
JAVA_OPTS='-Xmx1024m -Xms256m -XX:MaxPermSize=128m -XX:+HeapDumpOnOutOfMemoryError -XX:+AggressiveOpts -XX:+OptimizeStringConcat -XX:+UseFastAccessorMethods -XX:+UseParNewGC -XX:+UseConcMarkSweepGC -XX:+CMSParallelRemarkEnabled -XX:+CMSClassUnloadingEnabled -XX:SurvivorRatio=8 -XX:MaxTenuringThreshold=1 -XX:CMSInitiatingOccupancyFraction=75 -XX:+UseCMSInitiatingOccupancyOnly -Djava.awt.headless=true -server -Dxitrum.mode=production'
JAVA_OPTS="$JAVA_OPTS -Dxitrum.port.http=8002 -Dakka.remote.netty.tcp.port=2553"
# Quote because path may contain spaces
if [ -h $0 ]
then
  ROOT_DIR="$(cd "$(dirname "$(readlink -n "$0")")/.." && pwd)"
else
  ROOT_DIR="$(cd "$(dirname $0)/.." && pwd)"
fi
cd "$ROOT_DIR"

# Include ROOT_DIR to do "ps aux | grep java" to find this pid easier when
# starting multiple processes from different directories
CLASS_PATH="$ROOT_DIR/target/xitrum/lib/*:config"

# Use exec to be compatible with daemontools:
# http://cr.yp.to/daemontools.html
exec java $JAVA_OPTS -cp $CLASS_PATH leochat.Boot