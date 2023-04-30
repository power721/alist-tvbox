#!/bin/sh

java -jar alist-tvbox.jar --spring.profiles.active=production,xiaoya &

/entrypoint.sh "$@"