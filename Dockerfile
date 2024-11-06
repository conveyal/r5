FROM openjdk:21-slim-bullseye AS base


FROM base AS builder
WORKDIR /app

# Install unzip
RUN apt-get update && apt-get install -y unzip git

# Install Gradle 8.5
ENV GRADLE_VERSION=8.5
ADD https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip /tmp/gradle.zip
RUN unzip -d /opt/gradle /tmp/gradle.zip && \
    ln -s /opt/gradle/gradle-${GRADLE_VERSION} /opt/gradle/latest && \
    rm /tmp/gradle.zip

# Add Gradle to PATH
ENV PATH="/opt/gradle/latest/bin:${PATH}"

# Copy the project files
COPY . .

# Run gradle build with more verbose output
RUN gradle build

# Run gradle shadowJar
RUN gradle shadowJar

# Move the built jar, which is named by git describe, to r5.jar
RUN mv build/libs/r5-*-all.jar r5.jar


FROM base AS runner
WORKDIR /app
COPY --from=builder /app/r5.jar r5.jar
COPY analysis.properties.docker analysis.properties

EXPOSE 7070
CMD java -Xmx16g -cp r5.jar com.conveyal.analysis.BackendMain