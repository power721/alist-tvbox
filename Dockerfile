FROM amazoncorretto:17-alpine as corretto-deps

COPY target/alist-tvbox-1.0.jar /app/app.jar

RUN unzip /app/app.jar -d temp &&  \
    jdeps  \
      --print-module-deps \
      --ignore-missing-deps \
      --recursive \
      --multi-release 17 \
      --class-path="./temp/BOOT-INF/lib/*" \
      --module-path="./temp/BOOT-INF/lib/*" \
      /app/app.jar > /modules.txt

FROM amazoncorretto:17-alpine as corretto-jdk

COPY --from=corretto-deps /modules.txt /modules.txt

RUN apk add --no-cache binutils && \
    jlink \
     --verbose \
     --add-modules "$(cat /modules.txt),jdk.crypto.ec,jdk.crypto.cryptoki" \
     --strip-debug \
     --no-man-pages \
     --no-header-files \
     --compress=2 \
     --output /jre

FROM alpine:latest
ENV JAVA_HOME=/jre
ENV PATH="${JAVA_HOME}/bin:${PATH}"

COPY --from=corretto-jdk /jre $JAVA_HOME

LABEL MAINTAINER="Har01d"

VOLUME /opt/atv/data/

WORKDIR /opt/atv/

COPY target/alist-tvbox-1.0.jar ./alist-tvbox.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "alist-tvbox.jar", "--spring.profiles.active=production,docker"]