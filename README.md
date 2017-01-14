# R<sup>5</sup>: Rapid Realistic Routing on Real-world and Reimagined networks

R<sup>5</sup> is a fast routing engine for multimodal (transit/bike/walk/car) networks. It is currently designed
for analytic applications but there are plans to eventually have it support point-to-point journey planning.
The routing is "realistic" because it works by planning many trips over a time window, which is more reflective of how people
use the transportation system than planning a single trip at an exact departure time---very few people leave for work at precisely
7:53 AM every morning, and even fewer leave at a precise time when going to a non-work event. There's more information on our thinking
on this particular point [here](http://conveyal.com/blog/2015/05/04/variation-in-accessibility-measures).

Please follow the Conveyal Java style guide at https://github.com/conveyal/JavaStyle/

## History

R<sup>5</sup> grew out of several open-source projects. The focus on analytic applications, and the core development team behind R<sup>5</sup>,
came from [OpenTripPlanner](http://opentripplanner.org). Many of the algorithms, as well as the name, came from [r4](https://github.com/bliksemlabs/rrrr).

## Building a network

R5 is developed primarily as a routing library for use in other projects (Conveyal Analysis, Modeify etc.) However for testing purposes there are commands to build a network and provide basic routing and visualization of network structure in a web interface. To build a network, place one or more GTFS feeds in a directory together with an OSM PBF file covering the same region. Then run `com.conveyal.r5.R5Main point --build /Users/me/path/to/inputs`, using the -Xmx switch to give the JVM a GB or two of memory if possible. This will create a file called `network.dat` in the same directory as the input files. Then run `com.conveyal.r5.R5Main point --graphs /path/to/input/files` to start up the web server. The routing interface should then be available at `http://localhost:8080/`, a somewhat more advanced interface at `http://localhost:8080/new.html` and a vector-based visualization for examining the contents of the network at `http://localhost:8080/debug.html`. For the debug visualization, you will need to zoom in fairly close before edges are loaded.

## Performing a Release

We are not currently using the Maven release plugin. To do so, we'd have to make R5 depend only on non-SNAPSHOT repos. Anyway, we don't want to perform releases from our local machines and prefer to let Travis CI do this. It provides a consistent build environment and there is no risk of stray local commits getting into a release.

However, our manual release process closely mimics what Maven release does. Releases must be tagged with git *annotated* commits, not *lightweight* commits. This is because `git describe` references the last annotated commit, and we use git describe as a way of specifying and identifying analyst woker versions. These annotated tags should systematically begin with the letter v because this is how our scripts recognize shaded JARs named with git describe output as opposed to un-shaded maven artifact JARs. For example:

```
[check that all dependencies are on non-SNAPSHOT versions]
[check on Travis that the build is currently passing]
[check that you have pulled all changes from origin]
[edit version in POM to 0.3.0]
git add pom.xml
git commit -m "Prepare version 0.3.0 release"
git tag -a v0.3.0 -m "Version 0.3.0 release"
git push origin v0.3.0
[edit version in POM to 0.4.0-SNAPSHOT]
git add pom.xml
git commit -m "Prepare next development iteration 0.4.0-SNAPSHOT"
git push
```

Note that the release must be tagged on the master branch, not a maintenance or feature branch. The maintenance branch for a particular release should be created *after* that release is tagged. This is because git-describe determines the version by tracing back through the history to the most recent tag. A tag on a non-master branch will never be seen.
