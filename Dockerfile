FROM haroldli/java:17 as corretto-jdk

FROM alpine:latest

LABEL MAINTAINER="Har01d"

ENV JAVA_HOME=/jre
ENV PATH="${JAVA_HOME}/bin:${PATH}"

COPY --from=corretto-jdk /jre $JAVA_HOME

VOLUME /opt/atv/data/

WORKDIR /opt/atv/

COPY target/alist-tvbox-1.0.jar ./alist-tvbox.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "alist-tvbox.jar", "--spring.profiles.active=production,docker"]