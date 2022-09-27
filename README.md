# Conveyal R5 Routing Engine

## R5: Rapid Realistic Routing on Real-world and Reimagined networks
R5 is the routing engine for [Conveyal](https://www.conveyal.com/learn), a web-based system that allows users to create transportation scenarios and evaluate them in terms of cumulative opportunities accessibility indicators. See the [Conveyal user manual](https://docs.conveyal.com/) for more information.

We refer to the routing method as "realistic" because it works by planning door-to-door trips at many different departure times in a time window, which better reflects how people use transportation systems than planning a single trip at an exact departure time. R5 handles both scheduled public transit and headway-based lines, using novel methods to characterize variation and uncertainty in travel times. It is designed for one-to-many and many-to-many travel-time calculations used in access indicators, offering substantially better performance than repeated calls to older tools that provide one-to-one routing results. For a comparison with OpenTripPlanner, see [this background](http://docs.opentripplanner.org/en/latest/Version-Comparison/#commentary-on-otp1-features-removed-from-otp2).

We say "Real-world and Reimagined" networks because R5's networks are built from widely available open OSM and GTFS data describing baseline transportation systems, but R5 includes a system for applying light-weight patches to those networks for immediate, interactive scenario comparison.

**Please note** that the Conveyal team does not provide technical support for third-party deployments. R5 is a component of a specialized commercial system, and we align development efforts with our roadmap and the needs of subscribers to our hosted service. This service is designed to facilitate secure online collaboration, user-friendly data management and scenario editing through a web interface, and complex calculations performed hundreds of times faster using a compute cluster. These design goals may not align well with other use cases. This project is open source primarily to ensure transparency and reproducibility in public planning and decision making processes, and in hopes that it may help researchers, students, and potential collaborators to understand and build upon our methodology.

While the Conveyal team provides ongoing support and compatibility to subscribers, third-party projects using R5 as a library may not work with future releases. R5 does not currently expose a stable programming interface ("API" or "SDK"). As we release new features, previous functions and data types may change. The practical effect is that third-party wrappers or language bindings (e.g., for R or Python) may need to continue using an older release of R5 for feature compatibility (though not necessarily result compatibility, as the methods used in R5 are now relatively mature). 

## Methodology

For details on the core methods implemented in Conveyal Analysis and R5, see:

* [Conway, Byrd, and van der Linden (2017)](https://keep.lib.asu.edu/items/127809)
* [Conway, Byrd, and van Eggermond (2018)](https://www.jtlu.org/index.php/jtlu/article/view/1074)
* [Conway and Stewart (2019)](https://files.indicatrix.org/Conway-Stewart-2019-Charlie-Fare-Constraints.pdf)

### Citations

The Conveyal team is always eager to see cutting-edge uses of our software, so feel free to send us a copy of any thesis, report, or paper produced using this software. We also ask that any academic or research publications using this software cite the papers above, where relevant and appropriate.

## Configuration

It is possible to run a Conveyal Analysis UI and backend locally (e.g. on your laptop), which should produce results identical to those from our hosted platform. However, the computations for more complex analyses may take quite a long time. Extension points in the source code allow the system to be tailored to cloud computing environments to enable faster parallel computation.

### Running Locally

To get started, copy the template configuration (`analysis.properties.tmp`) to `analysis.properties`.
To run locally, use the default values in the template configuration file. `offline=true` will create a local instance that avoids cloud-based storage, database, or authentication services.
By default, analysis-backend will use the `analysis` database in a local MongoDB instance, so you'll also need to install and start a MongoDB instance.

Database configuration variables include:

- `database-uri`: URI to your MongoDB cluster
- `database-name`: name of the database to use in your MongoDB cluster

## Building and running

Once you have configured `analysis.properties` and started MongoDB locally, you can build and run the analysis backend with `gradle runBackend`. If you have checked out a commit (such as a release tag) where you are sure all tests will pass, you can skip the tests with `gradle -x test runBackend`.

You can build a single self-contained JAR file containing all the dependencies with `gradle shadowJar` and start it with `java -Xmx2g -cp build/libs/r5-vX.Y.Z-all.jar com.conveyal.analysis.BackendMain`.

Once you have this backend running, follow the instructions to start the [analysis-ui frontend](https://github.com/conveyal/analysis-ui). Once that the UI is running, you should be able to log in without authentication (using the frontend URL, e.g. http://localhost:3000). 

## Creating a development environment

In order to do development on the frontend or backend, you'll need to set up a local development environment. We use [IntelliJ IDEA](https://www.jetbrains.com/idea/). The free/community edition is sufficient for working on R5. Import R5 into IntelliJ as a new project from existing sources. You can then create a run configuration for `com.conveyal.analysis.BackendMain`, which is the main class. You will need to configure the JVM options and properties file mentioned above.

By default, IntelliJ will follow common Gradle practice and build R5 using the "Gradle wrapper" approach, in which operating-system specific scripts are run that download and install a specific version of Gradle in the projet directory. We have encountered problems with this approach where IntelliJ seems to have insufficient control over the build/run/debug cycle. IntelliJ has its own internal implementation of the Gradle build process, and in our experience this works quite smoothly and is better integrated with the debug cycle. To switch to this appraoch, in the Gradle section of the IntelliJ settings, choose "Build and run using IntelliJ IDEA" and "Run tests using IntelliJ IDEA". Below that you may also want to choose "Use Gradle from specified location" to use your local system-wide copy.

## Structured Commit Messages

We use structured commit messages to help generate changelogs.

The first line of these messages is in the following format: `<type>(<scope>): <summary>` 

The `(<scope>)` is optional and is often a class name. The `<summary>` should be in the present tense. The type should be one of the following:

- feat: A new feature from the user point of view, not a new feature for the build.
- fix: A bug fix from the user point of view, not a fix to the build.
- docs: Changes to the user documentation, or to code comments.
- style: Formatting, semicolons, brackets, indentation, line breaks. No change to program logic.
- refactor: Changes to code which do not change behavior, e.g. renaming a variable.
- test: Adding tests, refactoring tests. No changes to user code.
- build: Updating build process, scripts, etc. No changes to user code.
- devops: Changes to code that only affect deployment, logging, etc. No changes to user code.
- chore: Any other changes causing no changes to user code.

The body of the commit message (if any) should begin after one blank line. 

From 2018 to 2020, we used major/minor/patch release numbering as suggested by https://www.conventionalcommits.org. Starting in 2021, we switched to major/minor release numbering, incrementing the minor version with regular feature releases and the major version only when there are substantial changes to the cluster computing components of our system. Because there is no public API at this time, the conventional definition of breaking changes under semantic versioning does not apply. 
