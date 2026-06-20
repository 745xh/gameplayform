FROM eclipse-temurin:17-jdk
WORKDIR /app
COPY GameServer.java .
COPY public ./public
RUN javac -encoding UTF-8 GameServer.java
EXPOSE 5000
CMD ["java", "GameServer"]