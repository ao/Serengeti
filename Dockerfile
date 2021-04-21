FROM openjdk:8
COPY ./target/Serengeti-1.2-SNAPSHOT-jar-with-dependencies.jar /tmp
WORKDIR /tmp
EXPOSE 1985
#ENTRYPOINT ["java","gl.ao.serengeti.Serengeti"]
CMD java -jar Serengeti-1.2-SNAPSHOT-jar-with-dependencies.jar
