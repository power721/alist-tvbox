#!/usr/bin/env bash

cd /opt

apt-get update
apt-get -y install build-essential wget zlib1g-dev

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
find /opt -name 'graalvm-jdk-*' -exec ln -sf {} graalvm \;

VERSION=3.8.6
wget -O /tmp/maven.tgz https://archive.apache.org/dist/maven/maven-3/${VERSION}/binaries/apache-maven-${VERSION}-bin.tar.gz
tar xf /tmp/maven.tgz -C /opt
ln -sf apache-maven-${VERSION} maven
