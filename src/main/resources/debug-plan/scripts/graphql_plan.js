var hostname = "http://localhost:8080";
var my_map;
var MARIBOR_COOR = [46.562483, 15.643975];
var layer = null;
var graphqlResponse = null;

var PlanConfig = function() {
    this.accessModes="WALK";
    this.egressModes="WALK";
    this.directModes="WALK,BICYCLE";
    this.transitModes="BUS";
    this.date="2015-02-05";
    this.fromTime="7:30";
    this.toTime="10:30";
    this.fromLat = "";
    this.fromLon = "";
    this.toLat = "";
    this.toLon = "";
    this.plan = requestPlan;
    this.showReachedStops = requestStops;
};

var template = "";

$.ajax({
	url: "/scripts/request_query.json",
	dataType: "text",
        success: function(data) {
            template = data;
            console.log("Loaded data template");
        }
});



var planConfig = new PlanConfig();
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

    planConfig.fromLat = m1.getLatLng().lat;
    planConfig.fromLon = m1.getLatLng().lng;

    m1.on('move', function(e) {
        planConfig.fromLat = e.latlng.lat;
        planConfig.fromLon = e.latlng.lng;
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
    planConfig.toLat = m2.getLatLng().lat;
    planConfig.toLon = m2.getLatLng().lng;
    m2.on('move', function(e) {
        planConfig.toLat = e.latlng.lat;
        planConfig.toLon = e.latlng.lng;
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

function getDash (mode) {
    if (mode == 'WALK' || mode === 'BICYCLE' || mode === 'CAR') {
        return null;
    }
    return [5,10]
};
function styleMode(feature) {
    return {
        color: getModeColor(feature.properties.mode),
        dashArray: getDash(feature.properties.mode),
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
    console.log("Move marker", which);
    var m;
    if (which == "from") {
        m = m1;
    } else {
        m = m2;
    }
    if (m != undefined) {
        m.setLatLng(L.latLng(planConfig[which+"Lat"], planConfig[which+"Lon"]));
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

function getStopFeature(stop) {
    var stopFeature = {
        "properties":{
            "name": stop.name,
            "id": stop.id
        },
        "type":"Feature",
        "geometry":{
            "type":"Point",
            "coordinates":[
                stop.lon,
                stop.lat
            ]
        }
    };
    return stopFeature;
}


function getTransitFeature(fromStop, toStop, route) {
    var transitFeature = {
        "properties":{
            "mode":route.mode,
            "line":route.shortName,
            "info":fromStop.name + " -> " + toStop.name
        },
        "type": "Feature",
        "geometry": {
            "type": "LineString",
            "coordinates":
            [
                [fromStop.lon, fromStop.lat],
                [toStop.lon, toStop.lat]
            ],
        }
    };
    return transitFeature;
}


function getFeature(streetSegment) {
    var accesFeature = {
        "properties":{
            "mode":streetSegment.mode
        },
        "type": "Feature",
        "geometry": JSON.parse(streetSegment.geometryGeoJSON)
    };
    return accesFeature;
}

function showItinerary(optionIdx, itineraryIdx) {
    console.info("Option", optionIdx, "Itinerary", itineraryIdx);
    var option = graphqlResponse.data.plan.options[optionIdx];
    var itinerary = option.itinerary[itineraryIdx];
    console.log(itinerary);
    var access = option.access;
    var egress = option.egress;
    var transit = option.transit;
    //Removes line from previous request
    if (layer != null) {
        layer.clearLayers();
    }
    var connection = itinerary.connection;
    var accessData = access[connection.access];
    var features = {
        "type": "FeatureCollection",
        "features": []
    };
    var rentedBike=false;
    for(var edgeIdx=0; edgeIdx < accessData.streetEdges.length; edgeIdx++) {
        var curStreetEdge = accessData.streetEdges[edgeIdx];
        var curStreetEdgeFeature = getFeature(curStreetEdge);
        curStreetEdgeFeature.properties.distance = curStreetEdge.distance/1000;
        curStreetEdgeFeature.properties.aDir = curStreetEdge.absoluteDirection;
        curStreetEdgeFeature.properties.relDir = curStreetEdge.relativeDirection;
        if (curStreetEdge.bikeRentalOnStation != null) {
            /*console.log("On: ", curStreetEdge.bikeRentalOnStation);*/
            var bikeFeature = getStopFeature(curStreetEdge.bikeRentalOnStation);
            bikeFeature.properties.which = "ON";
            features.features.push(bikeFeature);
            rentedBike = true;
        }
        if (curStreetEdge.bikeRentalOffStation != null) {
            /*console.log("Off: ", curStreetEdge.bikeRentalOffStation);*/
            var bikeFeature = getStopFeature(curStreetEdge.bikeRentalOffStation);
            bikeFeature.properties.which = "OFF";
            features.features.push(bikeFeature);
            rentedBike = false;
        }
        if (accessData.mode == "BICYCLE" && curStreetEdge.mode == "WALK") {
            curStreetEdgeFeature.properties.info = "WALK_BICYCLE";
        }
        if (accessData.mode == "BICYCLE_RENT") {
            if (rentedBike) {
                if(curStreetEdge.mode == "WALK") {
                    curStreetEdgeFeature.properties.info = "WALK_RENTED_BICYCLE";
                } else {
                    curStreetEdgeFeature.properties.info = "RENTED_BICYCLE";
                }
            }
        }
        features.features.push(curStreetEdgeFeature);
    }
    if (connection.transit !== null) {
        for(var k=0;k < connection.transit.length; k++) {
            var transitInfo = connection.transit[k];
            var transitData = transit[k];
            var patternInfo = transitData.segmentPatterns[transitInfo.pattern];
            var route;
            for (var routeIdx=0; routeIdx < transitData.routes.length; routeIdx++) {
                var currentRoute= transitData.routes[routeIdx];
                if (currentRoute.routeIdx === patternInfo.routeIdx) {
                    route = currentRoute;
                    break;
                }
            }
            var fromStop = transitData.from;
            var toStop = transitData.to;
            var transitFeature = getTransitFeature(fromStop, toStop, route);
            var fromTime = patternInfo.fromDepartureTime[transitInfo.time];
            var toTime = patternInfo.toArrivalTime[transitInfo.time];
            transitFeature.properties.patternId = patternInfo.patternId;
            transitFeature.properties.fromTime = fromTime;
            transitFeature.properties.toTime = toTime;
            features.features.push(transitFeature);
            features.features.push(getStopFeature(fromStop));
            features.features.push(getStopFeature(toStop));
            if (transitData["middle"] != null) {
                var middleData = transitData["middle"]
                features.features.push(getFeature(middleData));
            }
            
        }
    }
    if (connection.egress !== null) { 
        var egressData = egress[connection.egress];
        var egressFeature = getFeature(egressData);
        features.features.push(egressFeature);
    }
    layer = L.geoJson(features, {
        pointToLayer:function(feature, latlng) {
            return L.circleMarker(latlng, {filColor:getModeColor(feature.properties.mode), radius:10, opacity:0.8, weight:1});
        },
        style: styleMode, onEachFeature:onEachFeature});
    layer.addTo(window.my_map);
}

function secondsToTime(seconds) { 
    return new Date(seconds * 1000).toISOString().substr(11, 8);
}

function makeTextResponse(data) {
    var options = data.data.plan.options;
    console.info("Options:", options.length);
    $(".response").html("");
    //Removes line from previous request
    if (layer != null) {
        layer.clearLayers();
    }
    var infos = "<ul>";
    for(var i=0; i < options.length; i++) {
        var option = options[i];
        var item = "<li>"+option.summary;
        var access = option.access;
        var egress = option.egress;
        var transit = option.transit;
        console.log("Summary:", option.summary);
        for(var j=0; j < option.itinerary.length; j++) {
            item+="<br /><a href=\"#\" class=\"itinerary\" data-option=\""+i+"\" data-itinerary=\""+j+"\">Itinerary:</a>";
            item+="<ul>";
            var itinerary=option.itinerary[j];
            item+="<li>waitingTime: "+secondsToTime(itinerary.waitingTime)+"</li>";
            item+="<li>walkTime: "+secondsToTime(itinerary.walkTime)+"</li>";
            item+="<li>transitTime: "+secondsToTime(itinerary.transitTime)+"</li>";
            item+="<li>duration: "+secondsToTime(itinerary.duration)+"</li>";
            item+="<li>transfers: "+itinerary.transfers+"</li>";
            item+="<li>distance: "+itinerary.distance/1000+"m</li>";
            item+="<li>startTime: "+itinerary.startTime+"</li>";
            item+="<li>endTime: "+itinerary.endTime+"</li>";
            var connection = itinerary.connection
            var accessData = access[connection.access];
            item+="<ol>";
            item+="<li>Mode:"+accessData.mode+" Duration: " + secondsToTime(accessData.duration) + "Distance: "+accessData.distance/1000 + "m</li>";
            if (connection.transit !== null) {
                for(var k=0;k < connection.transit.length; k++) {
                    var transitInfo = connection.transit[k];
                    var transitData = transit[k];
                    var patternInfo = transitData.segmentPatterns[transitInfo.pattern];
                    var route;
                    for (var routeIdx=0; routeIdx < transitData.routes.length; routeIdx++) {
                        var currentRoute= transitData.routes[routeIdx];
                        if (currentRoute.routeIdx === patternInfo.routeIdx) {
                            route = currentRoute;
                            break;
                        }
                    }
                    var fromTime = patternInfo.fromDepartureTime[transitInfo.time];
                    var toTime = patternInfo.toArrivalTime[transitInfo.time];
                    item+="<li>Mode: " + route.mode + "<br />From:"+transitData.from.name+" --> "+transitData.to.name+ "<br /> Pattern:";
                    item+=patternInfo.patternId+ " Line:" + route.shortName + " " +fromTime+" --> " +toTime+ "</li>";
                    if (transitData["middle"] != null) {
                        var middleData = transitData["middle"]
                        item+="<li>Mode:"+middleData.mode+" Duration: " + secondsToTime(middleData.duration) + "Distance: "+middleData.distance/1000 + "m</li>";
                    }

                }

            }
            if (connection.egress !== null) { 
                var egressData = egress[connection.egress];
                item+="<li>Mode:"+egressData.mode+" Duration: " + secondsToTime(egressData.duration) + "Distance: "+egressData.distance/1000 + "m</li>";
            }

            item+="</ol>";

            item+="</ul>";
        }

        item+="</li>";
        infos+=item;
    }
    $(".response").append(infos+"</ul>");
    $(".itinerary").click(function() {
        var option = $(this).data("option");
        var itinerary = $(this).data("itinerary");
        showItinerary(option, itinerary);
    });

}

function requestPlan() {
    var request = template
                .replace("FROMLAT", planConfig.fromLat)
                .replace("FROMLON", planConfig.fromLon)
                .replace("TOLAT", planConfig.toLat)
                .replace("TOLON", planConfig.toLon)
                .replace("DATE", planConfig.date)
                .replace("FROMTIME", planConfig.fromTime)
                .replace("TOTIME", planConfig.toTime)
                .replace("DIRECTMODES", planConfig.directModes)
                .replace("ACCESSMODES", planConfig.accessModes)
                .replace("EGRESSMODES", planConfig.egressModes)
                .replace("TRANSITMODES", planConfig.transitModes);
    var params = {
        'query': request,
        'variables': null
    };

    /*console.log(request);*/

    //make a request
    $.ajax({
        data: JSON.stringify(params),
        method: 'POST',
        contentType:"application/json; charset=utf-8",
        url: hostname + "/otp/routers/default/index/graphql",
        success: function (data) {
            console.log(data);
            graphqlResponse=data;
            makeTextResponse(data);
            /*
            if (data.errors) {
                alert(data.errors);
            }
            if (data.data) {
                layer = L.geoJson(data.data, {style: styleMode, onEachFeature:onEachFeature});
                layer.addTo(window.my_map);
            }
            */
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

    var gui = new dat.GUI();
    gui.remember(planConfig);
    gui.add(planConfig, "accessModes");
    gui.add(planConfig, "egressModes");
    gui.add(planConfig, "directModes");
    gui.add(planConfig, "transitModes");
    gui.add(planConfig, "date");
    gui.add(planConfig, "fromTime");
    gui.add(planConfig, "toTime");
    gui.add(planConfig, "fromLat").listen().onFinishChange(function(value) { moveMarker("from");});
    gui.add(planConfig, "fromLon").listen().onFinishChange(function(value) {moveMarker("from"); });
    gui.add(planConfig, "toLat").listen().onFinishChange(function(value) {moveMarker("to"); });
    gui.add(planConfig, "toLon").listen().onFinishChange(function(value) {moveMarker("to"); });
    gui.add(planConfig, "plan");
    /*gui.add(planConfig, "showReachedStops");*/
var sidebar = L.control.sidebar('sidebar').addTo(my_map);

});

