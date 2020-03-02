# R<sup>5</sup>: Rapid Realistic Routing on Real-world and Reimagined networks

R<sup>5</sup> is a routing engine for multimodal (transit/bike/walk/car) networks, with attention given to speed and efficient use of resources. It is intended primarily for analysis applications (one-to-many trees, travel time matrices, and cumulative opportunities accessibility indicators) but it also has basic support for point-to-point journey planning which may expand over time.

We refer to the routing method as "realistic" because it works by planning many trips at different departure times in a time window, which better reflects how people use transportation system than planning a single trip at an exact departure time. There's more information on our thinking on this particular point [here](http://conveyal.com/blog/2015/05/04/variation-in-accessibility-measures), [in this TRR article](https://repository.asu.edu/items/54162), and in [this JTLU article](https://www.jtlu.org/index.php/jtlu/article/view/1074), which we encourage you to cite if you use R5 in research publications.

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

## Structured Commit Messages

We use structured commit messages to allow automated tools to determine release version numbers and generate changelogs.

The first line of these messages is in the following format: `<type>(<scope>): <summary>`

The `(<scope>)` is optional. The `<summary>` should be in the present tense. The type should be one of the following:

- feat: A new feature from the user point of view, not a new feature for the build.
- fix: A bug fix from the user point of view, not a fix to the build.
- docs: Changes to the user documentation, or to code comments.
- style: Formatting, semicolons, brackets, indentation, line breaks. No change to program logic.
- refactor: Changes to code which do not change behavior, e.g. renaming a variable.
- test: Adding tests, refactoring tests. No changes to user code.
- chore: Updating build process, scripts, etc. No changes to user code.

The body of the commit message (if any) should begin after one blank line. If the commit meets the definition of a major version change according to semantic versioning (e.g. a change in API visible to an external module), the commit message body should begin with `BREAKING CHANGE: <description>`.

Presence of a `fix` commit in a release should increment the number in the third (PATCH) position.
Presence of a `feat` commit in a release should increment the number in the second (MINOR) position.
Presence of a `BREAKING CHANGE` commit in a release should increment the number in the first (MAJOR) position.

This is based on https://www.conventionalcommits.org.
