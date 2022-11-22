FROM openjdk:8-jdk-alpine
EXPOSE 8080
COPY target/alist-tvbox-1.0.jar alist-tvbox.jar
ENTRYPOINT ["java","-jar","/alist-tvbox.jar", "--spring.profiles.active=production"]