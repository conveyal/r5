# Static analysis output

R5 has the capability to output a bunch of flat files when performing an analysis, which can then
be stored in (for example) Amazon S3 and used to power front-end applications. The initial goal
is to produce entirely static sites for performing accessibility analysis.

## Conceptual framing

We first need to start by explaining some of the concepts of how R5's realistic routing works, because, in order
to make storing the output of an entire analysis run feasible, we have to store some intermediate outputs. R5 performs
transport analysis on regular [Web Mercator](https://en.wikipedia.org/wiki/Web_Mercator) grids at zoom level 10, which
is ideal for presenting analysis results on a map in the browser, as getting the value for a particular pixel on the map
is a simple bit shift. Zoom level 10 pixels are 153m across at the equator, and get smaller as you move towards the poles.

The search process in R5 consists of three stages: initial street search, transit search, and propagation. The initial street search
starts at the origin and finds nearby transit stops, along with the access times to them. The transit search starts at the stops that were
reached during the initial street search, and then finds the travel times from each of those stops to every other stop, using a modified
[RAPTOR](http://research.microsoft.com/pubs/156567/raptor_alenex.pdf) algorithm. This is where the realistic component of R5 comes in:
in order to model the way people actually use transit (leaving when they need to leave, not building their lives around the schedule),
we perform a search at every minute over the time window, using a version of the range-RAPTOR algorithm described in the aforementioned
paper that has been modified to support frequency-based routes. Once you've done that, you have arrival times at every transit stop
for every possible departure minute from the origin. You then search again on the street from every reached transit stop for every departure
minute and get the travel time to every Web Mercator grid cell for each departure minute. This is then generally summarized using a statistic
such as the average.

To make this work quickly on the client without requiring enormous amounts of storage space on the server, we have to employ a few tricks.
(It is quite straightforward to come up with a storage approach that seems good in theory, but ends up requiring literally petabytes of disk space once
it is computed for every origin in a large metropolitan area. We typically use the entirety of the Netherlands as a benchmark, as it is the
largest network we work with regularly.) The first trick is that we precompute each of the aforementioned phases of the search separately.

For the initial stop search, we precompute which transit stops are near each pixel, and store this list as well as the access times for each transit stop.
For each origin pixel, we also make a small image (41x41pixels) centered on the 
origin pixel which shows the non-transit travel time to nearby pixels; this avoids donuts of low accessibility around the origin due to being forced to take transit when
it would really be quicker to just walk to the destination.

For the transit search, we precompute the travel time from every
transit stop to every other transit stop in the network for every departure minute in the time window. We also include as many minutes after the time window
as the maximum number of minutes allowed to access transit. If your departure time window is from 7 AM to 9 AM, and you can spend up to 20 minutes walking
to transit, you could potentially be boarding transit as late as 9:20 AM.

To construct isochrones around a given origin (we'll get to accessibility in a bit), one first snaps that origin to the appropriate Web Mercator cell.
For each minute in the departure window, one uses the index of nearby transit stops to determine when the user would arrive at each transit stop
assuming they had left the origin at the time specified. One then uses the precomputed stop-to-stop travel times for the minute at which the user
arrived at the stop to determine at what minute they will arrive at every other stop in the network. For each pixel in the grid and every departure minute,
one then checks the computed arrival time at all nearby stops for that departure minute, adds the time needed to get from that stop to the pixel
(for the time being we use the same index used for the initial stop search, which assumes that the street network is symmetrical, which is true for walking).
After doing this, you have for every pixel the travel time for every departure time, which can then be summarized to a single value using standard statistical
techniques, e.g. the average.

Finally, to compute accessibility, we overlay these travel time values on opportunity data that has been projected into the same web mercator grids.

## Data format

The data storage consists of several files that are used to generate isochrone and accessibility results.

### Region-wide files

There is only one of each of these files for each query. All binary files are signed unless otherwise noted (this is because Java does not have unsigned types).
All files are gzipped, which should be transparent when delivered to the browser.

#### `query.json`

This includes various information about the parameters used to run the query, including the complete profile request used for the request.

TODO: EXAMPLE

#### `stop_trees.dat`

This contains the connection from Web Mercator pixels to transit stops. It is a row-first flat stream of the connections for each pixel in the query
(as defined in the query.json). For each pixel, the following data structure is present:

```
(2 bytes) number of transit stops reachable from this pixel
	for each reachable stop:
	(4 bytes) stop ID
	(2 bytes) travel time to stop, seconds
```

This file does not obviously have constant offsets. It is assumed that the client will build an index for the file when it is loaded. If this proves
to be too slow, we can pregenerate an index for the file.

### Per-transit-stop file

There is one file per transit stop, which contains transit travel times in seconds from that transit stop to every other stop in the network. It is formatted
as follows:

```
(4 bytes) number of transit stops
(2 bytes) number of departure minutes
for each transit stop:
	(2 bytes) travel time to stop, seconds, at first departure minute, with value -1 indicating unreachability
	for each departure minute after first:
		(2 bytes) delta from previous travel time (in order to reduce entropy and allow gzip to efficiently compress data)
```

### Per-origin files

There are other files which are replicated once for each pixel. Each has an x and y pixel value, which is relative to the top-left of the query
(the top-left pixel being [0, 0]). They are stored in a directory structure similar to the way map tiles are stored, with a directory for each x value,
and a file named with the y value, and then appropriate file extension. The per-origin files are described below.

#### `non-transit/{x}/{y}.png`

This contains the non-transit access times to each nearby pixel. The center pixel of the image is the pixel specified by x and y in the filename.
It is a grayscale png with an odd number of rows and columns; the value (0-254) of each pixel is the travel time from the origin to that pixel in _minutes_, with 255
indicating unreachable.

## Calculating accessibility

All of the opportunity categories will be stored in similar flat files. After computing travel times over the entire departure window, accessibility should be calculated
over the entire window, and then averages should be taken. (It is important to take the averages of the accessibility, rather than taking average travel time and
using that to compute average accessibility; see [OpenTripPlanner issue 2148](https://github.com/opentripplanner/OpenTripPlanner/issues/2148))
