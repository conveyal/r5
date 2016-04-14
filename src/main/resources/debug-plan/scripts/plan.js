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

function requestParkRide() {
    var params = {
        fromLat: m1.getLatLng().lat,
        fromLon: m1.getLatLng().lng,
        mode: $("#mode").val(),
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

