# ENTUR Point-to-point routing based on r5

Her is a temporary description of the ENTUR project to create a point-to-point routing engine. The paln is to integrate this into OTP, but to speed up development, the development is done here in the r5 GitRepo - not in OTP - for the time being.


## Running the Speed Test
We use a test to verify the performance and test different travel searches. This is the best way to explore the algorithm. The SpeedTest gets it input from the `travelSearch.csv` file.

### Test data setup
You need to have:
 1. A GFTS zip file
 2. A OSM map file for the same area.
 3. A CSV test data file with one routing request per line(`travelSearch.csv`).

The best way to get started is to run with the provided _Norwegian_ test data set.

1. Copy the `[Git repo root]/testdata/norway` directory to you prefered testdata directory, `<test-data-dir>`. Some of the needed files are checked in, but you must download GTFS transit data and a osm map.
1. We did NOT provide the OSM map for norway so you have to download it. Download `norway-latest.osm.pdf` from   
[ geofabrik.de](https://download.geofabrik.de/europe/norway.html). Move it to `<test-data-dir>`. 
1. Take a look at the `<test-data-dir>/travelSearch.csv`, each line is a search and is run as part of the test.


That's it!


### Building a Network file

Run: `java -Xmx12G <cp> com.conveyal.r5.R5Main point --build <test-data-dir>`

It is possible that you can build the graph with less memory than 12G.

This creates a `network.dat` file in your `<test-data-dir>` directory.


### Running the SpeedTest

1. Edit the `<test-data-dir>/travelSearch.csv` to match your test data
1. Edit the `SpeedTest#buildDefaultRequest()`, set at least the `date`.
1. Run the test.

#### Speed test program options

Run: `java -Xmx12G <cp> com.conveyal.r5.speed_test.SpeedTest -h`

This should output something like this:
```
usage: [options]
 -c,--testCases <arg>                   A coma separated list of test case numbers to run.
 -d,--dir <arg>                         The directory where network and input files are located. (Optional)
 -D,--debug                             Enable debug info.
 -h,--help                              Print all command line options, then exit. (Optional)
 -i,--numOfItineraries <arg>            Number of itineraries to return.
 -m,--searchTimeWindowInMinutes <arg>   The time in minutes to add to from to time.
 -n,--sampleTestNTimes <arg>            Repeat the test N times. Profiles are altered in a round robin fashion.
 -p,--profiles <arg>                    A coma separated list of configuration profiles:
                                        'original' or 'or' : The original R5 FastRaptorWorker by Conveyal
                                        'range_raptor' or 'rr' : Standard Range Raptor, super fast [ transfers, arrival time, travel time ].
                                        'mc_range_raptor' or 'mc' : Multi-Criteria pareto optimal Range Raptor [ transfers, arrival time,
                                        travel time, walking ].
 -s,--debugStops <arg>                  A coma separated list of stops to debug.
 -t,--debugTrip <arg>                   A coma separated list of stops representing a trip/path to debug. Use a '*' to indicate where to
                                        start debugging. For example '1,*2,3' will print event at stop 2 and 3, but not stop 1 for all trips
                                        starting with the given stop sequence.
 -v,--verbose                           Verbose output, print itineraries.
 ```

The arguments might be slightly different in your version, but the most useful arguments are:

- `-d <test-data-dir>` 
- `-p rr`, `-p mc`, or `-p mc,rr` Run the test with _Standard Range Raptor_(fast), _Pareto-optimal-multicriteria Range Raptor_ (slow), or both to compare results.
- `-v` Print all paths/itineraries even if the test passes. Itineraries for failing tests are always printed. 
- `-n 8` Repeat the test _8_ times to get better performance measurement results (The `-p rr` is so fast that you need to repeat the test to measure the performance more accurate).
- `-c 21` or `-c 3,7,21` Run a given set of tests. Use the `testcaseId` from the `travelSearch.csv` file.  
- `-t 86128,86121,84932` Debug a trip. This print every event affecting the specified trip, this is great for understanding how raptor works and to find out why a trip is dropped in favor of another trip.


#### Setting the request parameters

Some of the request parameters are hard-coded in the speed test. Edit the `SpeedTest#buildDefaultRequest()` to match your transit data, at least the `date` must be a valid service date.


#### Run the speed test

To run the test with using the super fast Standard Range Raptor algorithm use:

Run: `java -Xmx12G <cp> com.conveyal.r5.speed_test.SpeedTest -d <test-data-dir> -p rr`


### Comparison and verification of the result

The SpeedTest read the expected results from the `travelSearch-expected-results.csv` file. The expected results are compared with the actual result found in the travel search. The SpeedTest also write its result to a new file, the `travelSearch-expected-results-out.csv` each time it run.

If there is a mismatch between the expected and the actual the diff is printed to standard error:
```
SpeedTest FAILED     27 ms  #21 Indre Billefjord - Karasjok bussterminal, (70,315, 25,047) - (69,473, 25,517)  - Test assert errors  (TestCaseFailedException) 
      STATUS       | TF | Duration | Walk |   Start  |    End   | Modes | Agencies | Routes  | Stops             | Legs                                                                                      
WARN! Not expected |  1 |  1:56:12 |   12 | 09:08:59 | 11:05:11 |  BUS  |    Sne   | 066 063 | 86128 86121 84932 | WALK 0:01 - 86128 - BUS 066 09:10 09:45 - 86121 - BUS 063 10:00 11:05 - 84932 - WALK 0:11 
 FAILED! Expected  |  1 |  2:10:33 | 1051 | 08:54:38 | 11:05:11 |   -   |     -    | -       | -                 | WALK 13:22 - 86061 - BUS 066 09:09 09:45 - 86121 - BUS 063 10:00 11:05 - 84932 - WALK 0:11
        OK         |  0 |  2:10:33 | 1051 | 14:09:38 | 16:20:11 |  BUS  |    Sne   | 063     | 86061 84932       | WALK 13:22 - 86061 - BUS 063 14:24 16:20 - 84932 - WALK 0:11                              
``` 
Not all information is saved/retrieved from the `travelSearch-expected-results.csv` file, so the value for there fields are just a '-' in the table. 


In the `ItineraryResultMapper` there is a map of agencies/abbreviations used in the debug logging. You may want to add your agencies to get pretty results. 


### Running the HTTP Server
We have copied the OTP GUI, and set up a minimalistic HTTP Server to work with the Speed Test. We rarely use it, so notify us if it does not work.

TODO TGR 


