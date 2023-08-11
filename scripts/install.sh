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

wget -O musl.tgz https://more.musl.cc/10.2.1/x86_64-linux-musl/x86_64-linux-musl-native.tgz
tar xf musl.tgz

export TOOLCHAIN_DIR=/opt/x86_64-linux-musl-native/
export CC=$TOOLCHAIN_DIR/bin/gcc
export PATH="$TOOLCHAIN_DIR/bin:$PATH"

wget https://zlib.net/zlib-1.2.13.tar.gz
tar xf zlib-1.2.13.tar.gz
cd zlib-1.2.13
./configure --prefix=$TOOLCHAIN_DIR --static
make
make install

rm -f graalvm.tgz maven.tgz musl.tgz zlib-1.2.13.tar.gz

#echo 'export TOOLCHAIN_DIR=/opt/x86_64-linux-musl-native' > /etc/profile.d/env.sh
#echo 'export CC="$TOOLCHAIN_DIR/bin/gcc"' >> /etc/profile.d/env.sh
#echo 'export MAVEN_HOME=/opt/maven' >> /etc/profile.d/env.sh
#echo 'export JAVA_HOME=/opt/graalvm' >> /etc/profile.d/env.sh
#echo 'export GRAALVM_HOME=/opt/graalvm' >> /etc/profile.d/env.sh
#echo 'export PATH="${JAVA_HOME}/bin:${MAVEN_HOME}/bin:$TOOLCHAIN_DIR/bin:${PATH}"' >> /etc/profile.d/env.sh
