#!/usr/bin/env bash

if command -v arch >/dev/null 2>&1; then
  platform=$(arch)
else
  platform=$(uname -m)
fi

if [ "$platform" = "aarch64" ]; then
  wget https://download.oracle.com/graalvm/17/latest/graalvm-jdk-17_linux-aarch64_bin.tar.gz -O /tmp/graalvm.tgz
else
  wget https://download.oracle.com/graalvm/17/latest/graalvm-jdk-17_linux-x64_bin.tar.gz -O /tmp/graalvm.tgz
fi

tar xf /tmp/graalvm.tgz  -C /opt
ln -sf /opt/graalvm-jdk-17_* /opt/graalvm

VERSION=3.8.6
wget -O /tmp/maven.tgz https://archive.apache.org/dist/maven/maven-3/${VERSION}/binaries/apache-maven-${VERSION}-bin.tar.gz
tar xf /tmp/maven.tgz -C /opt

ls -l /opt
