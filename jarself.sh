#!/bin/sh
SELF=`which "$0" 2>/dev/null`
[ $? -gt 0 -a -f "$0" ] && SELF="./$0"
exec java -jar $SELF "$@"
exit 1
