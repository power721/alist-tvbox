FROM haroldli/alist-base:latest

LABEL MAINTAINER="Har01d"

ARG TARGETARCH

ENV MEM_OPT="-Xmx1024M" ALIST_PORT=5344 INSTALL=new

COPY config/alist.json /

COPY scripts/entrypoint.sh /
COPY scripts/init.sh /
COPY scripts/tg-provider-runtime.sh /tg-provider-runtime.sh
RUN chmod +x /tg-provider-runtime.sh

COPY data/tvbox.zip /
COPY data/base_version /
COPY data/cat.zip /
COPY data/pg.zip /
COPY data/zx.zip /

COPY build/tg-provider/linux-${TARGETARCH}/tg-provider /usr/local/bin/tg-provider
RUN chmod +x /usr/local/bin/tg-provider
COPY target/application/ ./

COPY data/version /app_version

EXPOSE 4567 5244

ENTRYPOINT ["/entrypoint.sh"]

CMD ["81", "--spring.profiles.active=production,docker"]
