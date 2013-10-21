#!/bin/sh
if [ -h $0 ]
then
  ROOT_DIR="$(cd "$(dirname "$(readlink -n "$0")")/.." && pwd)"
else
  ROOT_DIR="$(cd "$(dirname $0)/.." && pwd)"
fi
cd "$ROOT_DIR"
cp config/application2.conf config/application.conf
sbt/sbt run