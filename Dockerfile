FROM registry.access.redhat.com/ubi9/openjdk-21-runtime

WORKDIR /deployments

COPY build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java","-jar","app.jar"]
