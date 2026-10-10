# Alter2 game server image.
#
#   docker compose up -d --build     build and start (see docker-compose.yml for the volumes it needs)
#   docker compose stop              graceful: SIGTERM runs the shutdown hook, which saves every player
#
# The image holds only the server's libraries. The data directory (cache, rsa key, configs, saves) and
# game.yml come from the host, so the same image serves any checkout of the repository.

FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY . .
RUN chmod +x gradlew && ./gradlew --no-daemon --console=plain :game-server:installDist

FROM eclipse-temurin:17-jre
# The server resolves ../data and ../game.yml, so it runs from /app/game-server.
WORKDIR /app/game-server
COPY --from=build /src/game-server/build/install/game-server/lib ./lib
ENV ALTER_JAVA_OPTS="-Xmx3g"
EXPOSE 43594
# exec makes java PID 1, so `docker stop` delivers SIGTERM to it. Exit code 75 means "restart requested"
# (::update); the compose restart policy starts the container again.
ENTRYPOINT ["sh", "-c", "exec java $ALTER_JAVA_OPTS -cp '/app/game-server/lib/*' org.alter.game.Launcher"]
