Here are descriptive speeds from OSM
This is used when setting speed from OSM maxspeed tag since sometimes CH:urban is used in maxspeed tag instead of source:maxspeed.

Table is copied from https://wiki.openstreetmap.org/wiki/Speed_limits#Country_code.2Fcategory_conversion_table
read with Google spreadsheet with formula:

=IMPORTHTML("https://wiki.openstreetmap.org/wiki/Speed_limits", "table", 3)
Then this is saved as speeds.csv file and with python script convert.py converted to JSON. With name and numeric value.
Script saves only speeds which pass OSM speed regex.
