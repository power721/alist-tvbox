mvn clean package || exit 1

docker build -f Dockerfile-jre --tag=haroldli/java:17 .

docker push haroldli/java:17
