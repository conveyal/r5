# seamless-census

Import US Census data into a seamless storage environment.

## Usage

Running the download and load steps for the entire US requires ~45 GB of disk space.

### Download data

You can use the following command to download
data from the Census bureau. Create a temporary directory to receive the files before you combine them and load them to
S3, in a location that has plenty of disk space. The arguments are the temporary directory and the two-letter postal abbreviations
of the states for which you want to retrieve data (you can also use the special code ALL to retrieve data for every state, territory and district).
The command below, for instance, would download data for the greater Washington, DC megalopolis.

    python downloadData.py temporary_dir DC MD VA WV DE

### Load data

Use the same temporary directory
you used above. If you omit the s3 bucket name, it will place the tiles in the `tiles` directory in the temporary directory.

    JAVA_OPTS=-Xmx[several]G mvn exec:java -Dexec.mainClass="com.conveyal.data.census.CensusLoader" -Dexec.args="temporary_dir s3_bucket_name"

### Extract data

Now for the fun part. The following command will extract the data stored in the s3 bucket specified, using the bounding box specified,
to the geobuf file out.pbf.

    JAVA_OPTS=-Xmx[several]G mvn exec:java -Dexec.mainClass="com.conveyal.data.census.CensusExtractor" -Dexec.args="s3://bucket_name n e s w out.pbf"

## Data storage

Data is stored in a directory structure, which is kept in Amazon S3. Census data is split
up into zoom-level-11 tiles and stored in [GeoBuf](https://github.com/mapbox/geobuf) files, each
in a directory for its source, its x coordinate and named its y coordinate.  For example, `us-census-2012/342/815.pbf`
might contain US LODES data and decennial census data for southeastern Goleta, CA.

Enumeration units that fall into two tiles should be included in both tiles. It is the responsibility
of the data consumer to deduplicate them; this can be done based on IDs. An enumeration unit that is
duplicated across tiles must have the same integer ID in both tiles.

We have already loaded LODES data from 2013, 2014, 2015, and 2017 in the S3 buckets `lodes-data`, `lodes-data-2014`, `lodes-data-2015`, etc.
These buckets and their contents are publicly readable and requester-pays (i.e. accessing them will incur fees on your AWS account). 
The 2013 data lack Massachusetts, and uses 2011 data for Kansas, due to data availability. 
The 2014 and 2015 data do not have these problems.
The 2017 data exclude federal employees and use 2016 data for Alaska and South Dakota. See LODES Technical Documentation for details.

## Use in Conveyal Analysis

Any dataset that can be placed in this format can be used in [Conveyal Analysis](https://github.com/conveyal/analysis-ui)
