#!/usr/bin/env bash

cd /opt

apt-get update
apt-get -y install build-essential wget zlib1g-dev locales
rm -rf /var/lib/apt/lists/*
localedef -i en_US -c -f UTF-8 -A /usr/share/locale/locale.alias en_US.UTF-8

if command -v arch >/dev/null 2>&1; then
  platform=$(arch)
else
  platform=$(uname -m)
fi

if [ "$platform" = "aarch64" ]; then
  wget https://download.oracle.com/graalvm/17/latest/graalvm-jdk-17_linux-aarch64_bin.tar.gz -O graalvm.tgz
else
  wget https://download.oracle.com/graalvm/17/latest/graalvm-jdk-17_linux-x64_bin.tar.gz -O graalvm.tgz
fi

tar xf graalvm.tgz
find /opt -name 'graalvm-jdk-*' -exec ln -sf {} graalvm \;

VERSION=3.8.6
wget -O maven.tgz https://archive.apache.org/dist/maven/maven-3/${VERSION}/binaries/apache-maven-${VERSION}-bin.tar.gz
tar xf maven.tgz
ln -sf apache-maven-${VERSION} maven

if [ "$platform" = "aarch64" ]; then
  wget -O musl.tgz https://musl.cc/aarch64-linux-musl-cross.tgz
else
  wget -O musl.tgz https://more.musl.cc/10.2.1/x86_64-linux-musl/x86_64-linux-musl-native.tgz
fi

tar xf musl.tgz
find /opt -name '*-linux-musl-*' -exec ln -sf {} musl \;

export TOOLCHAIN_DIR=/opt/musl
export CC=$TOOLCHAIN_DIR/bin/gcc
export PATH="$TOOLCHAIN_DIR/bin:$PATH"

wget -O zlib.tgz https://zlib.net/zlib-1.2.13.tar.gz
tar xf zlib.tgz
cd zlib-1.2.13
./configure --prefix=$TOOLCHAIN_DIR --static
make
make install

rm -rf "*.tgz" zlib-1.2.13
