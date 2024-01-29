FROM golang:1.20 as BUILDER

WORKDIR /app/

COPY atv-cli ./

RUN go build

FROM haroldli/alist-base:latest

LABEL MAINTAINER="Har01d"

ENV MEM_OPT="-Xmx512M" ALIST_PORT=5344

COPY config/config.json /opt/alist/data/config.json

COPY --from=BUILDER /app/atv-cli /

COPY scripts/init.sh /
COPY scripts/alist.sql /
COPY movie.sh /
COPY scripts/entrypoint.sh /

COPY data/tvbox.zip /
COPY data/base_version /
COPY data/cat.zip /
COPY target/application/ ./

COPY data/version data/app_version

EXPOSE 4567 5244

ENTRYPOINT ["/entrypoint.sh"]

CMD ["81", "--spring.profiles.active=production,docker"]
