FROM openjdk:8-jdk-alpine

LABEL MAINTAINER="Har01d"

VOLUME /opt/atv/data/

WORKDIR /opt/atv/

COPY target/alist-tvbox-1.0.jar ./alist-tvbox.jar

EXPOSE 8080

ENTRYPOINT ["java","-jar","alist-tvbox.jar", "--spring.profiles.active=production,docker"]