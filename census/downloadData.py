#!/usr/bin/python
# Download the data we need for a particular [set of] states, or the entire country
# usage: downloadData.py outDir state_abbr [state_abbr . . .]
# Or use special code 'ALL'

from sys import argv
from urllib.request import urlretrieve
import zipfile
import os
import os.path
from shutil import copyfileobj
from time import sleep

# map from state abbreviations to FIPS codes
# per http://www.epa.gov/enviro/html/codes/state.html
fipsCodes = dict (
    AK = "02", # ALASKA
    AL = "01", # ALABAMA
    AR = "05", # ARKANSAS
    AS = "60", # AMERICAN SAMOA
    AZ = "04", # ARIZONA
    CA = "06", # CALIFORNIA
    CO = "08", # COLORADO
    CT = "09", # CONNECTICUT
    DC = "11", # DISTRICT OF COLUMBIA
    DE = "10", # DELAWARE
    FL = "12", # FLORIDA
    GA = "13", # GEORGIA
    GU = "66", # GUAM
    HI = "15", # HAWAII
    IA = "19", # IOWA
    ID = "16", # IDAHO
    IL = "17", # ILLINOIS
    IN = "18", # INDIANA
    KS = "20", # KANSAS
    KY = "21", # KENTUCKY
    LA = "22", # LOUISIANA
    MA = "25", # MASSACHUSETTS
    MD = "24", # MARYLAND
    ME = "23", # MAINE
    MI = "26", # MICHIGAN
    MN = "27", # MINNESOTA
    MO = "29", # MISSOURI
    MS = "28", # MISSISSIPPI
    MT = "30", # MONTANA
    NC = "37", # NORTH CAROLINA
    ND = "38", # NORTH DAKOTA
    NE = "31", # NEBRASKA
    NH = "33", # NEW HAMPSHIRE
    NJ = "34", # NEW JERSEY
    NM = "35", # NEW MEXICO
    NV = "32", # NEVADA
    NY = "36", # NEW YORK
    OH = "39", # OHIO
    OK = "40", # OKLAHOMA
    OR = "41", # OREGON
    PA = "42", # PENNSYLVANIA
    PR = "72", # PUERTO RICO
    RI = "44", # RHODE ISLAND
    SC = "45", # SOUTH CAROLINA
    SD = "46", # SOUTH DAKOTA
    TN = "47", # TENNESSEE
    TX = "48", # TEXAS
    UT = "49", # UTAH
    VA = "51", # VIRGINIA
    VI = "78", # VIRGIN ISLANDS
    VT = "50", # VERMONT
    WA = "53", # WASHINGTON
    WI = "55", # WISCONSIN
    WV = "54", # WEST VIRGINIA
    WY = "56", # WYOMING
)

# parse arguments
outDir = argv[1]
states = [state.upper() for state in argv[2:]]

if len(states) == 1 and states[0] == 'ALL':
    # download all states
    print("Downloading all states")
    states = fipsCodes.keys()

# check inputs
invalidStates = [state for state in states if not state in fipsCodes]

if len(invalidStates) > 0:
    print ("Did not recognize states %s" % ' '.join(invalidStates))

# make the directory structure
os.makedirs(os.path.join(outDir, 'tiger'))
os.makedirs(os.path.join(outDir, 'jobs'))
os.makedirs(os.path.join(outDir, 'workforce'))

# download resiliently
def retrieve(url, path):
    for i in range(50):
        try:
            print("download attempt {0}".format(i))
            urlretrieve(url, path)
        except:
            print("error retrieving {0}, retrying".format(url))
            sleep(5)
        else:
            break


# download the states
for state in states:
    print('processing %s' % state)
    print('Downloading TIGER')
    # get tiger
    fips = fipsCodes[state]
    zipout = os.path.join(outDir, "tiger", "{0}.zip".format(state))
    retrieve("ftp://ftp2.census.gov/geo/tiger/TIGER2010/TABBLOCK/2010/tl_2010_{0}_tabblock10.zip".format(fips), zipout)

    # unzip it
    # adapted from http://stackoverflow.com/questions/12886768/
    with zipfile.ZipFile(zipout) as zf:
        for member in zf.infolist():
            name = os.path.split(member.filename)[-1]
            dest = os.path.join(outDir, 'tiger', name)
            with zf.open(member) as stream:
                with open(dest, 'wb') as out:
                    copyfileobj(stream, out)

    # we no longer need the zipfile
    os.remove(zipout)

    print('Done with TIGER')

    print('Downloading LODES data')

    # figure out the year of the latest available data
    # Most states have 2015 data available
    # see http://lehd.ces.census.gov/data/lodes/LODES7/LODESTechDoc7.4.pdf, page 2f
    year = 2017

    # Alaska and South Dakota do not have LODES2017 data available, so use 2016
    if state == 'AK' or state == 'SD':
        year = 2016
    elif state == 'PR' or state == 'VI':
        print('{0} does not have LODES data available'.format(state))
        year = 0

    if year:
        print("Downloading {0} LODES data for {1}".format(year, state))

        # get the rac file
        out = os.path.join(outDir, 'workforce', '{0}_{1}_rac.csv.gz'.format(state, year))
        retrieve("http://lehd.ces.census.gov/data/lodes/LODES7/{0}/rac/{0}_rac_S000_JT00_{1}.csv.gz".format(state.lower(), year), out)

        # get the wac file
        out = os.path.join(outDir, 'jobs', '{0}_{1}_wac.csv.gz'.format(state, year))
        retrieve("http://lehd.ces.census.gov/data/lodes/LODES7/{0}/wac/{0}_wac_S000_JT00_{1}.csv.gz".format(state.lower(), year), out)

    print('Done with {0}'.format(state))
