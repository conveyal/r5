# R<sup>5</sup>: Rapid Realistic Routing on Real-world and Reimagined networks

R<sup>5</sup> is a routing engine for multimodal (transit/bike/walk/car) networks, with attention given to speed and efficient use of resources. It is intended primarily for analysis applications (one-to-many trees, travel time matrices, and cumulative opporunitites accessibility indicators) but it also has basic support for point-to-point journey planning which may expand over time.

We refer to the routing method as "realistic" because it works by planning many trips at different departure times in a time window, which better reflects how people use transportation system than planning a single trip at an exact departure time. There's more information on our thinking on this particular point [here](http://conveyal.com/blog/2015/05/04/variation-in-accessibility-measures), [in this TRB article](https://doi.org/10.3141/2653-06), and in [this JTLU article](https://www.jtlu.org/index.php/jtlu/article/view/1074), which we encourage you to cite if you use R5 in research publications.

We say "Real-world and Reimagined" networks because R5's networks are built from widely available open GTFS data describing existing transit service, but R5 includes a system for applying light-weight patches to those networks for immediate, interactive scenario comparison.

When contributing code, please follow the Conveyal Java style guide at https://github.com/conveyal/JavaStyle/

Javadoc for the project is built automatically after every change and published at http://javadoc.conveyal.com/r5/master/

This is a Maven project, so you'll need to install maven to build R<sup>5</sup> before running it. Build instructions are in pom.xml.  Once R<sup>5</sup> is built (e.g. using `mvn clean package`), it can be run (e.g. `java -jar -Xmx4G target/v3.0.0.jar worker` -- see list of commands [here](https://github.com/conveyal/r5/blob/master/src/main/java/com/conveyal/r5/R5Main.java)).

## History

R<sup>5</sup> grew out of several open-source projects. The focus on analytic applications, and the core development team behind R<sup>5</sup>,
came from [OpenTripPlanner](http://opentripplanner.org). Many of the algorithms, as well as the name, came from [r4](https://github.com/bliksemlabs/rrrr).

## Building a network

R5 is developed primarily as a routing library for use in other projects (Conveyal Analysis, Modeify etc.) However for testing purposes there are commands to build a network and provide basic routing and visualization of network structure in a web interface. To build a network, place one or more GTFS feeds in a directory together with an OSM PBF file covering the same region. Then run `com.conveyal.r5.R5Main point --build /Users/me/path/to/inputs`, using the -Xmx switch to give the JVM a GB or two of memory if possible. This will create a file called `network.dat` in the same directory as the input files. Then run `com.conveyal.r5.R5Main point --graphs /path/to/input/files` to start up the web server. The routing interface should then be available at `http://localhost:8080/`, a somewhat more advanced interface at `http://localhost:8080/new.html` and a vector-based visualization for examining the contents of the network at `http://localhost:8080/debug.html`. For the debug visualization, you will need to zoom in fairly close before edges are loaded.

## Performing a Release

Releases are automatically generated using [maven-semantic-release](https://github.com/conveyal/maven-semantic-release).
