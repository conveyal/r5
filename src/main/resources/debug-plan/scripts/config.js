var otp_config = {
    hostname: "http://localhost:8080",
    restService: "debug",
    routerId: "default"
};
//Those are layers and name of properties in a layer if detail=true
var layers = {
    streetEdges:["edge_id", "permission", "speed_ms", "flags", "osmid"],
    permEdges:["name", "edge_id", "label", "osmid"],
    turns:["edge_id", "permission", "speed_ms", "only", "edge", "via_edge_idx", "restrictionId", "osmid"]
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
var speed_visible = true;
//True if bidirectional edges are shown AKA direction arrows
var used_oneway_style = false;
var flag_filters = ["any"];

//Map of speed as m/s * 1000 as string to number of occurences
var speeds = {};
var speed_min = 0;
var speed_max = 36111;

//line-dasharray from MapGL first number is length of line second length of gap
var flag_dash = [1.25, 3];

//which color has which permission
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
            "data": full_url,
            "maxzoom":22
        }
    },
    "sprite": "oneway",
    "layers": [{
        "id": "simple-tiles",
        "type": "raster",
        "source": "simple-tiles",
        "minzoom": 0,
        "maxzoom": 18
    }]
};

//list of random colors to have some default colors for flags
var colors = [
    "#c7f7e8",
    "#58583b",
    "#4b4aac",
    "#e64444",
    "#e6345c",
    "#a9114c",
    "#2c6e7a",
    "#4ada8e",
    "#e54f96",
    "#e35647",
    "#429647",
    "#065947",
    "#b14937",
    "#7bd891",
    "#73ee9a",
    "#07b66e",
    "#67ba67",
    "#0f7d14",
    "#8f62a6",
    "#1e63e0",
    "#8de092",
    "#6b8c38",
    "#155e54",
    "#3b54e2",
    "#b20f03",
    "#9f8602",
    "#41c8c9",
    "#c385c5",
    "#d1c331",
    "#952165",
    "#6c2449",
    "#1356cb",
    "#5475b9",
    "#c3fb47",
    "#f5b41e",
    "#23bb24",
    "#941e96",
    "#172ab3",
    "#81943c",
    "#a24cf7",
];

//There are also different colored icons ["blue", "black", "green", "mix"]
//and different sizes ["thin", "middle", "big"]
var oneway_icons_style = {
    "id": "oneway-icons",
    "type": "symbol",
    "source": "perm",
    "minzoom":15,
    "maxzoom":22,
    "layout": {
        "symbol-placement": "line",
        "icon-image": {
            "base": 1,
            "stops": [
                [
                16,
                "oneway-middle-mix-small"
            ],
            [
                17,
                "oneway-big-mix-small"
            ]
            ]
        },
        "icon-ignore-placement": true,
        "symbol-spacing":250,
        "icon-allow-overlap":false,
        "icon-padding":2
    },
    "paint": {}
};

var traffic_lights_layer = {
    'id': "perm-traffic_lights",
    'type': "circle",
    "source": "perm",
    "minzoom":15,
    "maxzoom":22,
    "paint":{
        "circle-color":"#C00000",
        "circle-blur":0.3
    },
    "layout": {},
    "filter": ["==", "TRAFFIC_SIGNAL", true]
};

var park_ride_layer = {
    'id': "perm-park_ride",
    'type': "circle",
    "source": "perm",
    "minzoom":13,
    "maxzoom":22,
    "paint":{
        "circle-color":"#FF00FF",
        "circle-blur":0.5
    },
    "layout": {},
    "filter": ["==", "PARK_AND_RIDE", true]
};

var bike_share_layer = {
    'id': "perm-bike_share",
    'type': "circle",
    "source": "perm",
    "minzoom":13,
    "maxzoom":22,
    "paint":{
        "circle-color":"#00FFFF",
        "circle-blur":0.5
    },
    "layout": {},
    "filter": ["==", "BIKE_SHARING", true]
};

var transit_stop_layer = {
    'id': "perm-transit_stop",
    'type': "circle",
    "source": "perm",
    "minzoom":13,
    "maxzoom":22,
    "paint":{
        "circle-color":"#FFFF00",
        "circle-blur":0.5
    },
    "layout": {},
    "filter": ["==", "STOP", true]
};

$.ajax(url + "/speeds", {
        dataType: 'JSON',
        success: function(data) {
            if (data.data) {
                speeds = data.data;
                speed_min = data.min;
                speed_max = data.max;
                /*console.info("Loaded speeds data:", speeds);*/
            } else {
                alert("Problem getting speeds:" + data.errors);
            }
        }
});


var FilterConfig = function() {
    this.debug_type = "permissions";
    this.unit = "kmh";
    this.both = false;
    this.edge_id = "";

    this.min_speed_color = "#008000";
    this.middle_speed_color = "#FFFF00";
    this.max_speed_color = "#FF0000";

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
    $("#speed_info a").click(function() {
        if (speed_visible) {
            $("#speed_info p").hide();
            speed_visible = false;
            $("#speed_info a").text("Open");
        } else {
            $("#speed_info p").show();
            speed_visible = true;
            $("#speed_info a").text("Close");
        }
    });
});
