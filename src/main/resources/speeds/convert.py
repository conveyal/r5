import csv
import re
import json

#Regex copied from R5 and OSM to find valid maxspeeds since some are different depending on city/rural which means they are currently useless
regex = re.compile("([0-9][\\.0-9]+?)(?:[ ]?(kmh|km/h|kmph|kph|mph|knots))?$")
with open("./speeds.csv", "r") as file:
    data = csv.DictReader(file)
    output = {}
    for line in data:
        if regex.match(line["numeric equivalent"]):
            output[line["value"].lower()] = line["numeric equivalent"]
json.dump(output, open("speeds.json", "w"))

