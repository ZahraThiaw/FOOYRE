# syntax=docker/dockerfile:1
# FOOYRE — image de PRODUCTION du backend (Java 21, Spring Boot 4.1.2). Contexte de build : backend/.
#
# - Étape 1 : compilation avec le wrapper Maven du dépôt (versions figées par pom.xml). Les tests
#   ne sont PAS exécutés ici : ils nécessitent Docker (Testcontainers) et sont lancés avant chaque
#   déploiement (./mvnw verify), voir docs/deploiement-render.md.
# - Étape 2 : JRE 21 seul, utilisateur non‑root, aucun secret dans l'image : toute la configuration
#   arrive par variables d'environnement (profil prod, ProductionConfigurationGuard).
# Images de base officielles Eclipse Temurin (à figer par empreinte sha256 lors de l'audit sécurité).

FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
# Fin de ligne Unix garantie, même si l'arborescence locale a été extraite en CRLF.
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
# Dépendances dans une couche séparée : rebuild rapide quand seul le code change.
RUN ./mvnw -B -q dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -q -DskipTests package \
    && java -Djarmode=tools -jar target/fooyre-backend-*.jar extract --destination /workspace/extracted

FROM eclipse-temurin:21-jre-noble
# Utilisateur système dédié, sans shell de connexion ni droit d'écriture sur l'application.
RUN groupadd --system fooyre && useradd --system --gid fooyre --no-create-home --shell /usr/sbin/nologin fooyre
WORKDIR /app
COPY --from=build --chown=root:root /workspace/extracted/ ./
USER fooyre
ENV SPRING_PROFILES_ACTIVE=prod \
    TZ=UTC \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -XX:+UseSerialGC -Xss512k -Djava.security.egd=file:/dev/./urandom"
# Port indicatif : Render fournit le port réel dans PORT (application-prod.yml).
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
