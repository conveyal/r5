var otp_config = {
    hostname: "http://localhost:8080",
    restService: "debug",
    routerId: "default"
};
//Those are layers and name of properties in a layer if detail=true
var layers = {
    streetEdges:["edge_id", "permission", "speed", "flags"],
    permEdges:["name", "edge_id", "label"]
};
var current_layer = "streetEdges";
var url = otp_config.hostname + '/' + otp_config.restService;
var style;
var current_type="permissions";
var request_url = url+'/' + current_layer;
var full_url = request_url;
var tileset = 'conveyal.hml987j0';
var map;
var flag_visible = true;

var permission_colors = {
    "none":"#333333",
    "walk":"#33b333",
    "bike":"#3333b3",
    "walk,bike":"#33b3b3",
    "car":"#b33333",
    "walk,car":"#b3b333",
    "bike,car":"#b333b3",
    "walk,bike,car":"#b3b3b3"
};

var mapbox_style = {
    "version": 8,
    "sources": {
        "simple-tiles": {
            "type": "raster",
            "tiles": [ "http://a.tiles.mapbox.com/v3/" + tileset + "/{z}/{x}/{y}.png",
                "http://b.tiles.mapbox.com/v3/" + tileset + "/{z}/{x}/{y}.png"
            ],
            "maxzoom": 18,
            "tileSize": 256
        },
        "perm": {
            "type": "geojson",
            "data": full_url
        }
    },
    "layers": [{
        "id": "simple-tiles",
        "type": "raster",
        "source": "simple-tiles",
        "minzoom": 0,
        "maxzoom": 18
    }]
};

var FilterConfig = function() {
    this.debug_type = "permissions";

    this.show_unused = false;
    this.color_unused = "#b013b5";
    this.show_bike_path = false;
    this.color_bike_path = "#999f7c";
    this.show_sidewalk = false;
    this.color_sidewalk = "#367f74";
    this.show_crossing = false;
    this.color_crossing = "#682cab";
    this.show_roundabout = false;
    this.color_roundabout = "#21c021";
    this.show_elevator = false;
    this.color_elevator = "#8c6023";
    this.show_stairs = false;
    this.color_stairs = "#0e2e8c";
    this.show_platform = false;
    this.color_platform = "#0c5611";
    this.show_bogus_name = false;
    this.color_bogus_name = "#cb098f";
    this.show_no_thru_traffic = false;
    this.color_no_thru_traffic = "#b56031";
    this.show_slope_override = false;
    this.color_slope_override = "#f5627b";
    this.show_transit_link = false;
    this.color_transit_link = "#8448d3";
    this.show_allows_pedestrian = true;
    this.color_allows_pedestrian = "#affe1b";
    this.show_allows_bike = false;
    this.color_allows_bike = "#5b82fd";
    this.show_allows_car = false;
    this.color_allows_car = "#006d93";
    this.show_allows_wheelchair = false;
    this.color_allows_wheelchair = "#505bc3";
    this.show_bike_lts_1 = false;
    this.color_bike_lts_1 = "#fa9de1";
    this.show_bike_lts_2 = false;
    this.color_bike_lts_2 = "#d2a18c";
    this.show_bike_lts_3 = false;
    this.color_bike_lts_3 = "#f3df6a";
    this.show_bike_lts_4 = false;
    this.color_bike_lts_4 = "#a45189";
};

mapboxgl.accessToken = ''; //NOT needed

$(function() {
//    getStyle(current_type);
    map = new mapboxgl.Map({
        container: 'map', // container id
        style: mapbox_style,
        zoom: 14
    });
    $("#flag_info a").click(function() {
        if (flag_visible) {
            $("#flag_info p").hide();
            flag_visible = false;
            $("#flag_info a").text("Open");
        } else {
            $("#flag_info p").show();
            flag_visible = true;
            $("#flag_info a").text("Close");
        }
    });
});
