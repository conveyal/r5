var hostname = "http://localhost:8080";
var my_map;
var MARIBOR_COOR = [46.562483, 15.643975];
var layer = null;

var m1, m2;
var weightSize;

var icon_start = L.icon({
    iconUrl: 'images/marker-flag-start-shadowed.png',
    shadowUrl: null,
    iconSize: new L.Point(48, 49),
    iconAnchor: new L.Point(46, 42),
    popupAnchor: new L.Point(0, -16)
});
var icon_end = L.icon({
    iconUrl: 'images/marker-flag-end-shadowed.png',
    shadowUrl: null,
    iconSize: new L.Point(48, 49),
    iconAnchor: new L.Point(46, 42),
    popupAnchor: new L.Point(0, -16)
});

my_map  = L.map('map', {
    contextmenu: true,
    contextmenuWidth: 140,
    contextmenuItems: [
        {
        text: "Start Here",
        callback: addFirst
    }, {
        text: "End Here",
        callback: addLast
    }, {
        separator: true
    }, {
        text: "Center Map",
        callback: centerMap
    }, {
        text: "Zoom in",
        callback: zoomIn
    }, {
        text: "Zoom Out",
        callback: zoomOut
    }]
}).setView(L.latLng(MARIBOR_COOR[0], MARIBOR_COOR[1]), 13);

function enable_button() {
    if (m1 != undefined && m2 != undefined) {
        $("#planButton").prop('disabled', false);
    }
    if (m1 != undefined) {
        $("#stopButton").prop('disabled', false);
        $("#bikeShareButton").prop('disabled', false);
        $("#prButton").prop('disabled', false);
        $("#isochroneButton").prop('disabled', false);
    }
}

function addFirst(e) {
    if (typeof (m1) != 'undefined') {
        my_map.removeLayer(m1);
    }
    m1 = L.marker(e.latlng, {
        draggable: true,
        icon: icon_start
    });
    enable_button()

    $("#fromLat").val(m1.getLatLng().lat);
    $("#fromLon").val(m1.getLatLng().lng);

    m1.on('move', function(e) {
        $("#fromLat").val(e.latlng.lat);
        $("#fromLon").val(e.latlng.lng);
    });

    m1.addTo(my_map);
}

function addLast(e) {
    if (typeof (m2) != 'undefined') {
        my_map.removeLayer(m2);
    }
    m2 = L.marker(e.latlng, {
        draggable: true,
        icon: icon_end
    });
    enable_button()
    $("#toLat").val(m2.getLatLng().lat);
    $("#toLon").val(m2.getLatLng().lng);
    m2.on('move', function(e) {
        $("#toLat").val(e.latlng.lat);
        $("#toLon").val(e.latlng.lng);
    });

    m2.addTo(my_map);
}

function zoomIn() {
    my_map.zoomIn();
}

function zoomOut() {
    my_map.zoomOut();
}

function centerMap(e) {
    my_map.panTo(e.latlng);
}

function getModeColor (mode) {
    if (mode === 'WALK') return '#484'
    if (mode === 'BICYCLE') return '#0073e5'
    if (mode === 'SUBWAY') return '#f00'
    if (mode === 'RAIL') return '#b00'
    if (mode === 'BUS') return '#080'
    if (mode === 'TRAM') return '#800'
    if (mode === 'FERRY') return '#008'
    if (mode === 'CAR') return '#444'
    return '#aaa'
};
function styleMode(feature) {
    return {
        color: getModeColor(feature.properties.mode),
        //weight:speedWeight(feature.properties.speed_ms),
        //weight:feature.properties.weight/10,
        opacity: 0.8
    };
}
function styleStop(feature) {
    return {
        color: getModeColor(feature.properties.mode),
        //weight:speedWeight(feature.properties.speed_ms),
        //radius:feature.properties.weight/10,
        radius:weightSize(feature.properties.weight),
        opacity: 0.8
    };
}

function styleBikeShare(feature) {
    return {
        color: '#05684B',
        //weight:speedWeight(feature.properties.speed_ms),
        //radius:feature.properties.weight/10,
        radius:weightSize(feature.properties.weight),
        opacity: 0.8
    };
}

function styleIsochrone(feature) {
    return {
        fillColor: hslToHex((feature.properties.time * -0.017391304347826 + 125.217391304348) / 360, 1, 0.5)
    }
}

/**
 * Converts an HSL color value to RGB. Conversion formula
 * adapted from http://en.wikipedia.org/wiki/HSL_color_space.
 * Copied and modified from the following:
 * - http://stackoverflow.com/a/9493060/269834
 * - http://stackoverflow.com/a/5624139/269834
 * Assumes h, s, and l are contained in the set [0, 1] and
 * returns hex code.
 *
 * @param   {number}  h       The hue
 * @param   {number}  s       The saturation
 * @param   {number}  l       The lightness
 * @return  {String}           The RGB representation
 */
function hslToHex(h, s, l){
    var r, g, b;

    if(s == 0){
        r = g = b = l; // achromatic
    }else{
        var hue2rgb = function hue2rgb(p, q, t){
            if(t < 0) t += 1;
            if(t > 1) t -= 1;
            if(t < 1/6) return p + (q - p) * 6 * t;
            if(t < 1/2) return q;
            if(t < 2/3) return p + (q - p) * (2/3 - t) * 6;
            return p;
        }

        var q = l < 0.5 ? l * (1 + s) : l + s - l * s;
        var p = 2 * l - q;
        r = hue2rgb(p, q, h + 1/3);
        g = hue2rgb(p, q, h);
        b = hue2rgb(p, q, h - 1/3);
    }

    var componentToHex = function(c) {
        var hex = c.toString(16);
        return hex.length == 1 ? "0" + hex : hex;
    }

    return "#" + componentToHex(Math.round(r * 255)) + componentToHex(Math.round(g * 255)) + componentToHex(Math.round(b * 255));
}

function onEachFeature(feature, layer) {
    if (feature.properties) {
        var prop = feature.properties;
        var pop = "<p>";
        $.each(prop, function(name, value) {
            pop += name.toUpperCase();
            pop +=": ";
            pop += value;
            pop +="<br />";
        });
        pop +="</p>";
        layer.bindPopup(pop);
    }
}

function moveMarker(which) {
    var m;
    if (which == "from") {
        m = m1;
    } else {
        m = m2;
    }
    if (m != undefined) {
        m.setLatLng(L.latLng($("#"+which+"Lat").val(), $("#"+which+"Lon").val()));
    }

}

function requestStops() {
    var params = {
        fromLat: m1.getLatLng().lat,
        fromLon: m1.getLatLng().lng,
        mode: $("#mode").val(),
    }

    if (params.mode === 'TRANSIT') {
        return alert('Transit analysis only avaible with isochrones in this UI.')
    }

    console.log(params);
    //make a request
    $.ajax({
        data: params,
        url: hostname + "/reachedStops",
        success: function (data) {
            console.log(data);
            //Removes line from previous request
            if (layer != null) {
                layer.clearLayers();
            }
            if (data.errors) {
                alert(data.errors);
            }
            if (data.data) {
                //scales point size radius from 4-20 based on min max of point weight
                weightSize = d3.scale.linear()
                .domain(d3.extent(data.data.features, function(feature) { return feature.properties.weight;}))
                .range([4, 20]);
                layer = L.geoJson(data.data, {pointToLayer:function(feature, latlng) {
                    return L.circleMarker(latlng, { filColor:getModeColor(feature.properties.mode), weight:1});
                },
                    style: styleStop, onEachFeature:onEachFeature});
                layer.addTo(window.my_map);
            }
        }
    });

}

function requestBikeShares() {
    var params = {
        fromLat: m1.getLatLng().lat,
        fromLon: m1.getLatLng().lng,
        mode: $("#mode").val(),
    }

    if (params.mode === 'TRANSIT') {
        return alert('Transit analysis only avaible with isochrones in this UI.')
    }

    console.log(params);
    //make a request
    $.ajax({
        data: params,
        url: hostname + "/reachedBikeShares",
        success: function (data) {
            console.log(data);
            //Removes line from previous request
            if (layer != null) {
                layer.clearLayers();
            }
            if (data.errors) {
                alert(data.errors);
            }
            if (data.data) {
                //scales point size radius from 4-20 based on min max of point weight
                weightSize = d3.scale.linear()
                .domain(d3.extent(data.data.features, function(feature) { return feature.properties.weight;}))
                .range([4, 20]);
                layer = L.geoJson(data.data, {pointToLayer:function(feature, latlng) {
                    return L.circleMarker(latlng, { filColor:getModeColor(feature.properties.mode), weight:1});
                },
                    style: styleBikeShare, onEachFeature:onEachFeature});
                layer.addTo(window.my_map);
            }
        }
    });

}

function requestIsochrone() {
    var params = {
        fromLat: m1.getLatLng().lat,
        fromLon: m1.getLatLng().lng,
        mode: $("#mode").val(),
        returnDistinctAreas: true
    }
    console.log(params);
    //make a request
    $.ajax({
        data: params,
        url: hostname + "/isochrone",
        success: function (data) {
            console.log(data);
            //Removes line from previous request
            if (layer != null) {
                layer.clearLayers();
            }
            if (data.errors) {
                alert(data.errors);
            }
            if (data.data) {
                layer = L.geoJson(data.data, {
                    style: styleIsochrone,
                    onEachFeature: onEachFeature,

                    // default styling
                    stroke: false,
                    /*color: '#808080',
                    weight: 1,*/
                    fillOpacity: 0.4
                });
                layer.addTo(window.my_map);
            }
        }
    });
}

function requestParkRide() {
    var params = {
        fromLat: m1.getLatLng().lat,
        fromLon: m1.getLatLng().lng,
        mode: $("#mode").val(),
    }

    if (params.mode === 'TRANSIT') {
        return alert('Transit analysis only avaible with isochrones in this UI.')
    }

    console.log(params);
    //make a request
    $.ajax({
        data: params,
        url: hostname + "/reachedParkRide",
        success: function (data) {
            console.log(data);
            //Removes line from previous request
            if (layer != null) {
                layer.clearLayers();
            }
            if (data.errors) {
                alert(data.errors);
            }
            if (data.data) {
                //scales point size radius from 4-20 based on min max of point weight
                weightSize = d3.scale.linear()
                .domain(d3.extent(data.data.features, function(feature) { return feature.properties.weight;}))
                .range([4, 20]);
                layer = L.geoJson(data.data, {pointToLayer:function(feature, latlng) {
                    return L.circleMarker(latlng, { filColor:getModeColor(feature.properties.mode), weight:1});
                },
                    style: styleBikeShare, onEachFeature:onEachFeature});
                layer.addTo(window.my_map);
            }
        }
    });

}

function requestPlan() {
    var params = {
        fromLat: m1.getLatLng().lat,
        fromLon: m1.getLatLng().lng,
        toLat: m2.getLatLng().lat,
        toLon: m2.getLatLng().lng,
        mode: $("#mode").val(),
        full: $("#full").is(':checked')
    }

    if (params.mode === 'TRANSIT') {
        return alert('Transit analysis only avaible with isochrones in this UI.')
    }

    console.log(params);

    //make a request
    $.ajax({
        data: params,
        url: hostname + "/plan",
        success: function (data) {
            console.log(data);
            //Removes line from previous request
            if (layer != null) {
                layer.clearLayers();
            }
            if (data.errors) {
                alert(data.errors);
            }
            if (data.data) {
                layer = L.geoJson(data.data, {style: styleMode, onEachFeature:onEachFeature});
                layer.addTo(window.my_map);
            }
        }
    });
}

var osmLayer = L.tileLayer('http://{s}.tile.osm.org/{z}/{x}/{y}.png', {
    attribution: '&copy; <a href="http://osm.org/copyright">OpenStreetMap</a> contributors'
});

var osmConveyalLayer = L.tileLayer('http://{s}.tiles.mapbox.com/v3/conveyal.hml987j0/{z}/{x}/{y}.png',{
    attribution: '&copy; <a href="http://osm.org/copyright">OpenStreetMap</a> contributors'
});

var mapquestLayer = L.tileLayer('http://{s}.mqcdn.com/tiles/1.0.0/osm/{z}/{x}/{y}.png', {
    attribution: 'Data, imagery and map information provided by <a href="http://open.mapquest.co.uk" target="_blank">MapQuest</a>, <a href="http://www.openstreetmap.org/" target="_blank">OpenStreetMap</a> and contributors.',
    subdomains: ['otile1', 'otile2', 'otile3', 'otile4']
});
my_map.addLayer(osmConveyalLayer);
my_map.addControl(new L.Control.Layers({
    'OSM': osmLayer,
    'OSM Conveyal': osmConveyalLayer,
    'MapQuest': mapquestLayer
}));

//Sets map based on graph data from server
$.ajax(hostname+"/metadata", {
    dataType: 'JSON',
    success: function(data) {
        window.my_map.fitBounds([
            [data.envelope.minY, data.envelope.minX],
            [data.envelope.maxY, data.envelope.maxX]
        ]);
    }
});

$(document).ready(function() {
    $("#planButton").click(requestPlan);
    $("#stopButton").click(requestStops);
    $("#bikeShareButton").click(requestBikeShares);
    $("#prButton").click(requestParkRide);
    $("#isochroneButton").click(requestIsochrone);
    $("#full").change(requestPlan);
    $("#fromLat").keyup(function(e) {
        //Enter pressed
        if(e.keyCode == 13)
            {
                moveMarker("from");
            }
    });
    $("#fromLon").keyup(function(e) {
        //Enter pressed
        if(e.keyCode == 13)
            {
                moveMarker("from");
            }
    });
    $("#toLat").keyup(function(e) {
        //Enter pressed
        if(e.keyCode == 13)
            {
                moveMarker("to");
            }
    });
    $("#toLon").keyup(function(e) {
        //Enter pressed
        if(e.keyCode == 13)
            {
                moveMarker("to");
            }
    });

});

