var hostname = "http://localhost:8080";
var my_map;
var MARIBOR_COOR = [46.562483, 15.643975];
var layer = null;
var graphqlResponse = null;
var geojsonLayer = null;
var bikeSharesLayer = null;
var parkRidesLayer = null;

function pad(number, length){
    var str = "" + number
    while (str.length < length) {
        str = '0'+str
    }
    return str
}

var offset = new Date().getTimezoneOffset()
offset = ((offset<0? '+':'-')+ // Note the reversed sign!
          pad(parseInt(Math.abs(offset/60)), 2)+":"+
          pad(Math.abs(offset%60), 2))

var PlanConfig = function() {
    this.accessModes="WALK";
    this.egressModes="WALK";
    this.directModes="WALK,BICYCLE";
    this.transitModes="TRANSIT";
    this.date="2015-02-05";
    this.fromTime="07:30";
    this.toTime="10:30";
    this.fromLat = "";
    this.fromLon = "";
    this.toLat = "";
    this.toLon = "";
    this.bikeTrafficStress = 4;
    this.minBikeTime = 5;
    this.wheelchair = false;
    this.offset = offset;
    this.plan = requestPlan;
    this.showReachedStops = requestStops;
    this.showStops = false;
    this.showPR = false;
    this.showBS = false;
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
    if (mode === 'RAIL') return '#f50'
    if (mode === 'BUS') return '#080'
    if (mode === 'TRAM') return '#a0f'
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
    var style = {
        color: getModeColor(feature.properties.mode),
        //weight:speedWeight(feature.properties.speed_ms),
        //radius:feature.properties.weight/10,
        radius:6,
        opacity: 0.8
    };

    //For variable radius for reached stops
    if (feature.properties.weight) {
        style.radius = weightSize(feature.properties.weight);
    }


    return style;
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
    var id;
    if (stop.stopId != undefined) {
        id = stop.stopId;
    } else {
        id = stop.id;
    }
    var stopFeature = {
        "properties":{
            "name": stop.name,
            "id": id
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
        if (curStreetEdge.parkRide != null) {
            /*console.log("Off: ", curStreetEdge.bikeRentalOffStation);*/
            var parkRideFeature = getStopFeature(curStreetEdge.parkRide);
            //parkRideFeature.properties.which = "OFF";
            features.features.push(parkRideFeature);
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
        /*var egressFeature = getFeature(egressData);*/
        /*features.features.push(egressFeature);*/
        for(var edgeIdx=0; edgeIdx < egressData.streetEdges.length; edgeIdx++) {
            var curStreetEdge = egressData.streetEdges[edgeIdx];
            var curStreetEdgeFeature = getFeature(curStreetEdge);
            features.features.push(curStreetEdgeFeature);
        }

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
    var jsonPatterns = data.data.plan.patterns;
    //This is currently used to get WheelchairAccessibility information for each used trip
    //Transforms list of patterns to map where key is tripPatternIdx and value are tripPatternIdx
    // and map of trips
    var patterns = jsonPatterns.reduce(function(total, current) {
        //Transforms list of trips wih map where key is tripId
        // and value is tripInfo(tripId, serviceId,  wheelchairAccessible, bikesAllowed)
        var trips = current.trips.reduce(function(totalTrips, currentTrip) {
            totalTrips[currentTrip.tripId] = currentTrip;
            return totalTrips;
        }, {});
        current.trips = trips;
        total[current.tripPatternIdx] = current;
        return total;
    }, {});
    //console.info("Patterns:", patterns);
    //console.info("Options:", options.length);
    $(".response").html("");
    //Removes line from previous request
    if (layer != null) {
        layer.clearLayers();
    }
    var infos = "";
    for(var i=0; i < options.length; i++) {
        var option = options[i];
        var item = "<details><summary>"+option.summary+"</summary>";
        var access = option.access;
        var egress = option.egress;
        var transit = option.transit;
        console.log("Summary:", option.summary);
        for(var j=0; j < option.itinerary.length; j++) {
            item+="<p><a href=\"#\" class=\"itinerary\" data-option=\""+i+"\" data-itinerary=\""+j+"\">Itinerary:</a></p>";
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
                    var tripId = patternInfo.tripId[transitInfo.time];
                    var pPatternInfo = patterns[patternInfo.patternIdx];
                    var ptripInfo = pPatternInfo.trips[tripId];
                    item += "<li>Mode: " + route.mode + "<br/>"
                    item += "From: " + transitData.from.name + " (" + transitData.from.stopId + " <abbr title=\"Wheelchair accessible\">WA</abbr>: " + transitData.from.wheelchairBoarding +")<br/>";
                    item += "To: " + transitData.to.name + " (" + transitData.to.stopId + " <abbr title=\"Wheelchair accessible\">WA</abbr>: " + transitData.to.wheelchairBoarding +") <br />";
                    item += "Pattern: ";
                    item += patternInfo.patternId+ " Line:" + route.shortName + " " +fromTime+" --> " +toTime + "<br />";
                    item += "<abbr title=\"Bikes Allowed\">BA</abbr>:" + ptripInfo.bikesAllowed + " <abbr title=\"Wheelchair accessible\">WA</abbr>:" + ptripInfo.wheelchairAccessible + " <abbr title=\"Service ID\">SID</abbr>:" + ptripInfo.serviceId + " Trip ID: " + ptripInfo.tripId +"</li>";
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
        if (option.fares) {
            item+="<p>Fares:</p>";
            for(var j=0; j < option.fares.length; j++) {
                var fare = option.fares[j];
                var unit = "";
                //TODO: use https://gist.github.com/Fluidbyte/2973986 for this
                if (fare.currency == "USD") {
                    var symbol = " $";
                }
                item+="<ul>";
                item+="<li>type: "+fare.type+"</li>";
                item+="<li>low: "+fare.low.toFixed(2)+symbol+"</li>";
                item+="<li>peak: "+fare.peak.toFixed(2)+symbol+"</li>";
                item+="<li>senior: "+fare.senior.toFixed(2)+symbol+"</li>";
                item+="<li>transferReduction: "+fare.transferReduction+"</li>";
                item+="</ul>";
            }
        }

        item+="</details>";
        infos+=item;
    }
    $(".response").append(infos+"</ul>");
    $(".itinerary").click(function() {
        var option = $(this).data("option");
        var itinerary = $(this).data("itinerary");
        showItinerary(option, itinerary);
    });

}

function makeModes(modeName, javaModeName) {
    var modes = planConfig[modeName].split(",");
    var niceModes = modes.map(function(mode) { return javaModeName+"."+mode});

    return niceModes.join(",");
}

//Gets all the stops if zoom > 13 and showStops is checked in envelope which is visible map bounds
function getStops(params) {
    if (geojsonLayer != null) {
        window.my_map.removeLayer(geojsonLayer);
    }
    //on zooms lower then 13 we are visiting too many spatial index cells on a server and don't get an answer
    if (window.my_map.getZoom() < 13 || !planConfig.showStops) {
        return;
    }

    var url = hostname + "/seenStops";
    $.getJSON(url, params, function(data) {
        /*console.log("Got data");*/
        /*console.log(data);*/
        if (data.errors) {
            alert(data.errors);
        }
        geojsonLayer = L.geoJson(data, {pointToLayer:function(feature, latlng) {
            return L.circleMarker(latlng, { weight:1});
        },
            style: styleStop, onEachFeature:onEachFeature});
        geojsonLayer.addTo(window.my_map);
    });

}

function getParkRides(params) {
    if (parkRidesLayer != null) {
        window.my_map.removeLayer(parkRidesLayer);
    }
    //on zooms lower then 11 we are visiting too many spatial index cells on a server and don't get an answer
    if (window.my_map.getZoom() < 11 || !planConfig.showPR) {
        return;
    }

    var url = hostname + "/seenParkRides";
    $.getJSON(url, params, function(data) {
        /*console.log("Got data");*/
        /*console.log(data);*/
        if (data.errors) {
            alert(data.errors);
        }
        parkRidesLayer = L.geoJson(data, {pointToLayer:function(feature, latlng) {
            return L.circleMarker(latlng, { fillColor:'#f0f', opacity:0.8, radius:5, weight:1});
        },
             onEachFeature:onEachFeature});
        parkRidesLayer.addTo(window.my_map);
    });

}

function getBikeShares(params) {
    if (bikeSharesLayer != null) {
        window.my_map.removeLayer(bikeSharesLayer);
    }
    //on zooms lower then 13 we are visiting too many spatial index cells on a server and don't get an answer
    if (window.my_map.getZoom() < 13 || !planConfig.showBS) {
        return;
    }

    var url = hostname + "/seenBikeShares";
    $.getJSON(url, params, function(data) {
        /*console.log("Got data");*/
        /*console.log(data);*/
        if (data.errors) {
            alert(data.errors);
        }
        bikeSharesLayer = L.geoJson(data, {pointToLayer:function(feature, latlng) {
            return L.circleMarker(latlng, { fillColor:'#0ff', opacity:0.8, radius:5, weight:1});
        },
             onEachFeature:onEachFeature});
        bikeSharesLayer.addTo(window.my_map);
    });

}


function requestPlan() {
    $('#resultTab').removeClass("status-ok status-error").addClass("status-waiting");
    var request = template
                .replace("DIRECTMODES", planConfig.directModes)
                .replace("ACCESSMODES", planConfig.accessModes)
                .replace("EGRESSMODES", planConfig.egressModes)
                .replace("TRANSITMODES", planConfig.transitModes);
    var variables ={
            'fromLat':planConfig.fromLat,
            'fromLon':planConfig.fromLon,
            'toLat': planConfig.toLat,
            'toLon':planConfig.toLon,
            'wheelchair':planConfig.wheelchair,
            'fromTime':planConfig.date+"T"+planConfig.fromTime+planConfig.offset,
            'toTime':planConfig.date+"T"+planConfig.toTime+planConfig.offset,
            'minBikeTime': planConfig.minBikeTime,
            'bikeTrafficStress': planConfig.bikeTrafficStress
        };
    var params = {
        'query': request,
        'variables': JSON.stringify(variables)
    };
    
    //This is object with variables copied into URL hash so requests can be shared
    var filteredPlanConfig = {};
    //Copies only variables from planConfig
    Object.keys(planConfig)
        .filter(function(varname) {
            //Filters out functions
            return !(varname == "plan" || varname == "showReachedStops")
        })
        .forEach(function(key) {
            filteredPlanConfig[key] = planConfig[key];
        });
    var query = $.param(filteredPlanConfig);
    var URL = location.pathname + "?" + query;

    $("#requestLink").attr("href", URL);

    var compactedParams = JSON.stringify(params);
    //Removes useless spaces so that CURL CLI line is more compact
    compactedParams = compactedParams.replace(/\s{2,}/g, ' ');

    $(".query").html("<p>This can be copied into GraphiQL and played with</p><pre>"+request + "\n\nQuery Variables:\n" + JSON.stringify(variables, null, "  ") + "</pre>");
    $(".curl").html("<p>This can be used in CURL: <samp> curl 'http://localhost:8080/otp/routers/default/index/graphql'  -H 'Accept-Encoding: gzip, deflate' -H 'Content-Type: application/json; charset=UTF-8' --data-binary '" + compactedParams + "' --compressed </samp></p>");

    var java = "<p>This can be used to write tests because it sets ProfileRequest</p><pre class=\"pre-wrap\">";

    java += "//Loading graph\n" +
	 'String dir = "path to folder with graph";\nInputStream inputStream = new BufferedInputStream(new FileInputStream(new File(dir, "network.dat")));\ntransportNetwork = TransportNetwork.read(inputStream);\n//Optional used to get street names:\ntransportNetwork.readOSM(new File(dir, "osm.mapdb"));\npointToPointQuery = new PointToPointQuery(transportNetwork);\n\n';

    java += "ProfileRequest profileRequest = new ProfileRequest();\n";
    java +="//Set timezone to timezone of transport network\nprofileRequest.zoneId = transportNetwork.getTimeZone();\n";
    for (var varname in variables) {
        if (varname.indexOf("Time") == -1) {
            java+="profileRequest." + varname+" = " + variables[varname] + ";\n";
        }
    }

    java+="profileRequest.setTime(\"" + variables.fromTime + "\", \"" + variables.toTime + "\");\n\n";

    if (planConfig.transitModes != null && planConfig.transitModes.length > 2) {
        java+="profileRequest.transitModes = EnumSet.of(" + makeModes("transitModes", "TransitModes") + ");\n";
    }
    if (planConfig.accessModes != null && planConfig.accessModes.length > 2) {
        java+="profileRequest.accessModes = EnumSet.of(" + makeModes("accessModes", "LegMode") + ");\n";
    }
    if (planConfig.egressModes != null && planConfig.egressModes.length > 2) {
        java+="profileRequest.egressModes = EnumSet.of(" + makeModes("egressModes", "LegMode") + ");\n";
    }
    if (planConfig.directModes != null && planConfig.directModes.length > 2) {
        java+="profileRequest.directModes = EnumSet.of(" + makeModes("directModes", "LegMode") + ");\n";
    }


    java +="//Gets a response:\n";
    java +="ProfileResponse ProfileResponse = pointToPointQuery.getPlan(profileRequest);";
    java+= "</pre>";

    $(".java").html(java);

    /*console.log(request);*/

    //make a request
    $.ajax({
        data: JSON.stringify(params),
        method: 'POST',
        contentType:"application/json; charset=utf-8",
        url: hostname + "/otp/routers/default/index/graphql",
        success: function (data) {
            console.log(data);
            $('#resultTab').removeClass("status-waiting status-error").addClass("status-ok");
            graphqlResponse=data;

            if (data.errors) {
                showDataErrors(data);
            } else {
                makeTextResponse(data);
            }
            /*
            }
            if (data.data) {
                layer = L.geoJson(data.data, {style: styleMode, onEachFeature:onEachFeature});
                layer.addTo(window.my_map);
            }
            */
        },
        error:function (jqXHR) {
            var data = jqXHR.responseJSON;
            showDataErrors(data);
        }
    });
}

function showDataErrors(data) {
    if (data.errors) {
        $('#resultTab').removeClass("status-waiting status-ok").addClass("status-error");
        var msg = "";
        if ($.isArray(data.errors)) {
            for(var i=0; i < data.errors.length; i++) {
                msg+= data.errors[i].message + "\n";
            }
        } else {
            msg = data.errors;
        }
        alert(msg);
        $('#resultTab').removeClass("status-waiting status-ok status-error");
    }
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
    gui.add(planConfig, "wheelchair");
    gui.add(planConfig, "date");
    gui.add(planConfig, "fromTime");
    gui.add(planConfig, "toTime");
    gui.add(planConfig, "offset");
    gui.add(planConfig, "fromLat").listen().onFinishChange(function(value) { moveMarker("from");});
    gui.add(planConfig, "fromLon").listen().onFinishChange(function(value) {moveMarker("from"); });
    gui.add(planConfig, "toLat").listen().onFinishChange(function(value) {moveMarker("to"); });
    gui.add(planConfig, "toLon").listen().onFinishChange(function(value) {moveMarker("to"); });
    gui.add(planConfig, "bikeTrafficStress");
    gui.add(planConfig, "minBikeTime");
    gui.add(planConfig, "plan");
    gui.add(planConfig, "showStops");
    gui.add(planConfig, "showPR");
    gui.add(planConfig, "showBS");
    /*gui.add(planConfig, "showReachedStops");*/
var sidebar = L.control.sidebar('sidebar').addTo(my_map);
    $('#resultTab').click(function (){
        $('#resultTab').removeClass("status-waiting status-ok status-error");

    })
    //Sets GUI from hash values
    if (location.search != "") {
        
        // init url params
        urlParams = { };
        var match,
            pl     = /\+/g,  // Regex for replacing addition symbol with a space
            search = /([^&=]+)=?([^&]*)/g,
            decode = function (s) { return decodeURIComponent(s.replace(pl, " ")); },
            query  = window.location.search.substring(1);

        while (match = search.exec(query)) {
            var value = decode(match[2]);
            var key = decode(match[1]);

            //fromLat,fromLon toLat and toLon are actually floats
            if ((key.indexOf("toL") >= 0) || (key.indexOf("fromL") >= 0)) {
                value = parseFloat(value);
            //wheelchair needs to be boolean
            } else if (key == "wheelchair") {
                planConfig[key] = $.parseJSON(value.toLowerCase());
            } else {
                planConfig[key] = value;
            }
            //This is actually unnecessary except for latitudes and longitudes
            urlParams[key] = value;
        }

        // updates all gui controllers since we changed the values
        for (var i in gui.__controllers) {
            gui.__controllers[i].updateDisplay();
        }

        var fromEvent = {'latlng': L.latLng(urlParams['fromLat'], urlParams['fromLon'])};
        addFirst(fromEvent);
        var toEvent = {'latlng': L.latLng(urlParams['toLat'], urlParams['toLon'])};
        addLast(toEvent);

    }

    my_map.on('moveend', function() {
        var bbox = my_map.getBounds();
        var params = {
            n : bbox.getNorth(),
            s : bbox.getSouth(),
            e : bbox.getEast(),
            w : bbox.getWest(),
        };
        getStops(params);
        getParkRides(params);
        getBikeShares(params);
    });

});

