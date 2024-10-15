FROM ubuntu:noble

RUN apt-get update && apt-get install -y \
    wget \
    tar \
    && rm -rf /var/lib/apt/lists/*

# Download Bitcoin Core for ARM64
RUN wget https://bitcoincore.org/bin/bitcoin-core-25.0/bitcoin-25.0-aarch64-linux-gnu.tar.gz

RUN tar -xvf bitcoin-25.0-aarch64-linux-gnu.tar.gz
RUN mv bitcoin-25.0 /bitcoin

ENV PATH="${PATH}:/bitcoin/bin"
RUN mkdir /bitcoin/data
VOLUME /bitcoin/data

ENTRYPOINT ["/bitcoin/bin/bitcoind", "-datadir=/bitcoin/data", "-chain=regtest", "-zmqpubrawblock=tcp://0.0.0.0:28332", "-rpcuser=bitcoin", "-rpcpassword=password", "-port=18444", "-rpcport=18443", "-rpcbind=:18443", "-rpcallowip=0.0.0.0/0"]

EXPOSE 18443 18444 28332