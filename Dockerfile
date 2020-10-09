FROM openjdk:11
ARG r5version
ENV R5_VERSION=$r5version
ENV JVM_HEAP_GB=2
WORKDIR /r5
COPY build/libs/r5-${R5_VERSION}-all.jar .
COPY analysis.properties.template analysis.properties
EXPOSE 7070
CMD java -Xmx${JVM_HEAP_GB}g -cp r5-${R5_VERSION}-all.jar com.conveyal.analysis.BackendMain
