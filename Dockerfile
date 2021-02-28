# Build with:
# docker build . --build-arg r5version=$(gradle -q printVersion | head -n1)
# or
# docker build . --build-arg r5version=$(cat build/version.txt)
# We could instead run the Gradle build and/or fetch version information 
# using run actions within the Dockerfile
FROM openjdk:11
ARG r5version
ENV R5_VERSION=$r5version
ENV JVM_HEAP_GB=2
WORKDIR /r5
COPY build/libs/r5-${R5_VERSION}-all.jar .
# Use a configuration that connects to the database on another host (container)
COPY analysis.properties.docker analysis.properties
EXPOSE 7070
CMD java -Xmx${JVM_HEAP_GB}g -cp r5-${R5_VERSION}-all.jar com.conveyal.analysis.BackendMain
