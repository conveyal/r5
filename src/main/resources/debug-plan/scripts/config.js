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

var speeds = {};
var speed_min = 0;
var speed_max = 36111;

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

//list of random colors to have some default colors for lines
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

$.ajax(url + "/speeds", {
        dataType: 'JSON',
        success: function(data) {
            if (data.data) {
                speeds = data.data;
                speed_min = data.min;
                speed_max = data.max;
                console.info("Loaded speeds data:", speeds);
            } else {
                alert("Problem getting speeds:" + data.errors);
            }
        }
});


var FilterConfig = function() {
    this.debug_type = "permissions";
    this.both = false;

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
});
