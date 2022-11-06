FROM openjdk:8-jdk-alpine
MAINTAINER baeldung.com
COPY target/alist-tvbox-1.0.jar alist-tvbox.jar
ENTRYPOINT ["java","-jar","/alist-tvbox.jar"]