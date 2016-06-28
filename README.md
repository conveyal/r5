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

## Performing a Release

We are not currently using the Maven release plugin. To do so, we'd have to make R5 depend only on non-SNAPSHOT repos. The manual release process closely mimics what Maven release does. Releases must be tagged with git *annotated* commits, not *lightweight* commits. This is because `git describe` references the last annotated commit, and we use git describe as a way of specifying and identifying analyst woker versions. These annotated tags should systematically begin with the letter v because this is how our scripts recognize shaded JARs named with git describe output as opposed to un-shaded maven artifact JARs. For example:

```
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
