#!/usr/bin/python
# Take a CSV from LODES and make all values unique so we can be sure that our tests
# are working. Some columns are all zeros or are (almost) collinear, so accidentally
# switching them might not make the tests fail.

from csv import DictReader, DictWriter
from sys import argv

ct = 0
def nextVal():
    global ct
    ct += 1
    return ct

with open(argv[1]) as infile:
    reader = DictReader(infile)

    with open(argv[2], 'w') as outfile:
        writer = DictWriter(outfile, reader.fieldnames)
        writer.writeheader()

        for row in reader:
            writer.writerow({k: nextVal() if k.startswith('C') else v for k, v in row.iteritems()})
