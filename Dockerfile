FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY queueless-contracts ./queueless-contracts
COPY queueless-server ./queueless-server
COPY queueless-desktop/pom.xml ./queueless-desktop/pom.xml
RUN mvn -B -ntp -pl queueless-server -am package -DskipTests

FROM tomcat:10.1.60-jdk21-temurin
RUN groupadd --system queueless && useradd --system --gid queueless queueless \
    && rm -rf /usr/local/tomcat/webapps/* \
    && chown -R queueless:queueless /usr/local/tomcat
COPY --from=build --chown=queueless:queueless /build/queueless-server/target/queueless.war /usr/local/tomcat/webapps/queueless.war
COPY deployment/context.xml /usr/local/tomcat/conf/context.xml
RUN mkdir /usr/local/tomcat/webapps/queueless \
    && cd /usr/local/tomcat/webapps/queueless && jar -xf ../queueless.war \
    && chown -R queueless:queueless /usr/local/tomcat/webapps
USER queueless
EXPOSE 8080
CMD ["catalina.sh", "run"]
