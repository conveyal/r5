# Commands used to create OSM PBF data corresponding to the Fiji Ferry test GTFS
# Process is a bit roundabout to avoid using bounding boxes that span the 180 degree meridian.

# First filter Geofabrik Fiji data to only roads and platforms
osmium tags-filter fiji-latest.osm.pbf w/highway w/public_transport=platform w/railway=platform w/park_ride=yes r/type=restriction -o fiji-filtered.pbf -f pbf,add_metadata=false,pbf_dense_nodes=true

# Extract two small sections, one around each stop on either side of the antimeridian
osmium extract --strategy complete_ways --bbox 178.4032589647,-18.1706713885,178.4764627685,-18.1213456347 fiji-filtered.pbf -o fiji-suva.pbf
osmium extract --strategy complete_ways --bbox -179.9970112547,-16.8025646734,-179.8150897003,-16.6356004526 fiji-filtered.pbf -o fiji-matei.pbf

# Combine the two pieces into a single OSM PBF file
osmium cat fiji-suva.pbf fiji-matei.pbf -o fiji-ferry.pbf
