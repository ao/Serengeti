FROM openjdk:8
COPY ./out/production/ADD/ /tmp
WORKDIR /tmp
EXPOSE 1985
ENTRYPOINT ["java","gl.ao.serengeti.Serengeti"]