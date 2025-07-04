export MUSL_HOME=$PWD/musl-toolchain

sudo rm -rf musl-* zlib-*

curl -O https://musl.libc.org/releases/musl-1.2.5.tar.gz
curl -O https://zlib.net/fossils/zlib-1.2.13.tar.gz

tar -xzvf musl-1.2.5.tar.gz
pushd musl-1.2.5
./configure --prefix=$MUSL_HOME --static
sudo make && make install
popd

ln -s $MUSL_HOME/bin/musl-gcc $MUSL_HOME/bin/x86_64-linux-musl-gcc

export PATH="$MUSL_HOME/bin:$PATH"
x86_64-linux-musl-gcc --version

tar -xzvf zlib-1.2.13.tar.gz
pushd zlib-1.2.13
CC=musl-gcc ./configure --prefix=$MUSL_HOME --static
make && make install
popd
