//String startsWith polyfill
if (!String.prototype.startsWith) {
    String.prototype.startsWith = function(searchString, position) {
        position = position || 0;
        return this.indexOf(searchString, position) === position;
    };
}

//Makes title case
String.prototype.toProperCase = function () {
    return this.replace(/\w\S*/g, function(txt){return txt.charAt(0).toUpperCase() + txt.substr(1).toLowerCase();});
};

function clone(obj) {
    var copy;

    // Handle the 3 simple types, and null or undefined
    if (null == obj || "object" != typeof obj) return obj;

    // Handle Date
    if (obj instanceof Date) {
        copy = new Date();
        copy.setTime(obj.getTime());
        return copy;
    }

    // Handle Array
    if (obj instanceof Array) {
        copy = [];
        for (var i = 0, len = obj.length; i < len; i++) {
            copy[i] = clone(obj[i]);
        }
        return copy;
    }

    // Handle Object
    if (obj instanceof Object) {
        copy = {};
        for (var attr in obj) {
            if (obj.hasOwnProperty(attr)) copy[attr] = clone(obj[attr]);
        }
        return copy;
    }

    throw new Error("Unable to copy obj! Its type isn't supported.");
}

/**
 * Creates flag layer and updates flag filters
 *
 * It uses color, and dash from gui config
 */
function makeFlagLayer(flag_name) {
    var flag_layer = {
        "id": "perm-"+flag_name,
        "type": "line",
        "source": "perm",
        "paint": {
            "line-color":text["color_"+flag_name],
            "line-width":2
        },
        "filter":["==", flag_name.toUpperCase(), true]
    };
    if (text["dash_"+flag_name]) {
        flag_layer.paint["line-dasharray"] = flag_dash;
        flag_layer.paint["line-width"] = 3;
    }
    flag_filters.push(["==", flag_name.toUpperCase(), true]);
    return flag_layer;
}

/**
Function enables or disables layer depending on true or false gui value
**/
function flagChange(flag_name, value) {
    if (current_type != "flags") {
        return;
    }
    //console.log("Flag:", flag_name);
    //console.log("Info:", value);
    if (value == false) {
        var location=-1;
        //We check which index has current filter we want to disable
        //We start at 1 since at 0 is always "any"
        for (var idx = 1; idx < flag_filters.length; idx++) {
            if (flag_filters[idx][1]==flag_name.toUpperCase()){
                location = idx;
                break;
            }
        }
        /*console.log("Removing flag: ", flag_name);*/
        /*console.log("Location:", location);*/
        /*console.log(flag_filters.map(function(item){return item[1];}));*/
        map.removeLayer("perm-"+flag_name);
        if (location > 0) {
            //removes current flag filter from list
            flag_filters.splice(location, 1);
            /*console.log(flag_filters.map(function(item){return item[1];}));*/
            oneway_icons_style.filter=flag_filters;
            if (text.both) {
                map.setFilter("oneway-icons", flag_filters);
            }
        }
    } else {
        var flag_layer = makeFlagLayer(flag_name);
        /*console.log("Adding flag: ", flag_name);*/
        /*console.log(flag_filters.map(function(item){return item[1];}));*/
        oneway_icons_style.filter=flag_filters;
        //We remove and readd oneway icons, because it needs to be the last
        //filter to be drawn on top
        if (text.both) {
            map.removeLayer("oneway-icons");
            map.addLayer(flag_layer);
            map.addLayer(oneway_icons_style);
        } else {
            map.addLayer(flag_layer);
        }
    }
}

//Function changes color of specific flag during changing color in gui
function colorChange(name, color) {
    if (current_type == "flags") {
    //It is assumed that layer exists since it is enabled
        if(text["show_"+name] == true) {
            //console.log("Flag:", name);
            //console.log("color:", color);
            map.setPaintProperty("perm-"+name, "line-color", color);
        }
    } else if (current_type == "speeds") {
        var speed_midle = (speed_max-speed_min)/2;
        var speedColor = d3.scale.linear()
        .domain([speed_min, speed_midle, speed_max])
        .range([text.min_speed_color, text.middle_speed_color, text.max_speed_color]);
        $.each(speeds, function(speed, occurence) {
            map.setPaintProperty("speed-"+speed,"line-color", speedColor(speed));
        });
    }
}

//Function that enables/disables flag dash array
function dashChange(name, enabled) {
    if (current_type != "flags") {
        return;
    }

    if (text["show_"+name] == true) {

        if (enabled==true) {
            map.setPaintProperty("perm-"+name, "line-width", 3);
            map.setPaintProperty("perm-"+name, "line-dasharray", flag_dash);
        } else {
            map.setPaintProperty("perm-"+name, "line-width", 2);
            //TODO: change this to null when unsetting paint properties is
            //supported
            map.setPaintProperty("perm-"+name, "line-dasharray", [100,0]);
        }
    }
}

//Switches Mapbox GL style between permissions and viewing specific flags
function getStyle(type) {
    console.info(type);
    console.log(text);
    style = clone(mapbox_style);
    if (type == "turns") {
        current_layer = "turns";
    } else {
        current_layer = "streetEdges";
    }
    request_url = url + '/' + current_layer;
    full_url = request_url;
    flag_filters = ["any"];
    if (type == "permissions" || type == "turns") {
        $.each(permission_colors, function(name, color) {
            var nice_name = name.replace(',', '_');
            var permission_layer = {
                "id": "perm-"+nice_name,
                "type": "line",
                "source": "perm",
                "paint": {
                    "line-color":color,
                    "line-width":2
                },
                "interactive": true,
                "filter":["==", "permission", name]
            };
            style.layers.push(permission_layer);
        });
        var hover_layer = {
            "id": "perm-hover",
            "type": "line",
            "source": "perm",
            "paint": {
                "line-color":"red",
                "line-width":2
            },
            "filter": ["==", "edge_id", -1]
        };
        style.layers.push(hover_layer);
    } else if (type == "flags") {
        $.each(text, function(name, value) {
            console.log(name);
            if (name.startsWith("show_") && value == true) {
                var flag_name = name.replace("show_", "");
                var flag_layer = makeFlagLayer(flag_name);
                style.layers.push(flag_layer);
            }

        });
    } else if (type == "speeds") {
        var speed_midle = (speed_max-speed_min)/2;
        var speedColor = d3.scale.linear()
        .domain([speed_min, speed_midle, speed_max])
        .range([text.min_speed_color, text.middle_speed_color, text.max_speed_color]);

        $.each(speeds, function(speed, occurence) {
            var speed_layer = {
                "id": "speed-"+speed,
                "type": "line",
                "source": "perm",
                "interactive":true,
                "paint": {
                    "line-color":speedColor(speed),
                    "line-width":2
                },
                "filter":["==", "speed_ms", parseInt(speed)]
            };
            style.layers.push(speed_layer);
        });
        var hover_layer = {
            "id": "perm-hover",
            "type": "line",
            "source": "perm",
            "paint": {
                "line-color":"black",
                "line-width":2
            },
            "filter": ["==", "edge_id", -1]
        };
        style.layers.push(hover_layer);

    }
    //Updates oneway icons style since we can enable/disable it with show
    //bidirectional checkbox
    if (type === "flags") {
        oneway_icons_style.filter=flag_filters;
    } else {
        delete oneway_icons_style.filter;
    }
    if (text.both) {
        style.layers.push(oneway_icons_style);
        used_oneway_style = true;
        /*console.log("Added oneway icons");*/
    }
    //Vertex layers:
    style.layers.push(traffic_lights_layer);
    style.layers.push(park_ride_layer);
    style.layers.push(bike_share_layer);
    style.layers.push(transit_stop_layer);
    //console.log(style);
    map.setStyle(style);
    current_type = type;
}

//Converts speed from m/s to kmh/mph
// based on GUI settings
// And returnes it rounded to 2 decimal places
//and wanted unit
function showSpeed(speedms) {
    var speed_ms = speedms/100;
    var speed;
    if (text.unit == "kmh") {
        speed = speed_ms*3.6;
    } else {
        speed = speed_ms*2.23693629;
    }
    return Math.round(speed*100)/100 + " "+text.unit;
}

//Shows all the features from current layer in popup
function fillPopup(feature, layer) {
    if (feature.properties) {
        var prop = feature.properties;
        var pop = "<p>";
        var layer_info = layers[current_layer];
        for (var i=0; i < layer_info.length; i++) {
            pop += layer_info[i].toUpperCase();
            pop +=": ";
            if (layer_info[i] == "speed_ms") {
                pop += showSpeed(prop[layer_info[i]]);
            } else if (layer_info[i] == "osmid") {
                pop += "<a target='_blank' href='https://osm.org/way/"+ prop[layer_info[i]]+"'>OSM W:" + prop[layer_info[i]]+"</a>";
            } else {
                pop += prop[layer_info[i]];
            }
            pop +="<br />";
        }
        pop +="</p>";
        return pop;
    }
    return null;
}

//Shows how many times each flag is used in flag usage div
function showFlagInfos(data) {
    var html = "";
    $.each(data.data, function(flag, usage) {
        var split_name = flag.replace("_", " ");
        var nice_name = split_name.toProperCase();
        html += "<b>" + nice_name +"</b>:";
        html += usage + "<br />";
    });
    if (html !== "") {
        $(".flags").html(html);
    }
}

//Shows how many times each speed is used in speed usage div
function showSpeedInfos(data) {
    console.info(data);
    var html = "";
    $.each(data, function(speed, usage) {
        var speed = showSpeed(speed);
        html += "<b>" + speed +"</b>:";
        html += usage + "<br />";
    });
    if (html !== "") {
        $(".speeds").html(html);
    }
}

function updateMap() {
    //on zooms lower then 13 we are visiting too many spatial index cells on a server and don't get an answer
    if (map.getZoom() < 13) {
        return;
    }
    var bbox = map.getBounds();
    var params = {
        n : bbox.getNorth(),
        s : bbox.getSouth(),
        e : bbox.getEast(),
        w : bbox.getWest(),
        detail: true //adds name, OSMID and speed as properties
    };
    //Shows lines in both direction only on larger zooms
    if (map.getZoom() > 14) {
        params.both= text.both;
    }
    //Currently we are showing bidirectional lines but are switching to
    //monodirectional
    if (used_oneway_style && !params.both) {
        used_oneway_style = false;
        map.removeLayer("oneway-icons");
    //Switching to bidireactional
    }else if (!used_oneway_style && params.both) {
        used_oneway_style = true;
        map.addLayer(oneway_icons_style);
    }
    //Shows flag and speed stats
    $.ajax(url +"/stats",{
        dataType: 'JSON',
        data:params,
        success: function(data) {
            if (data.data) {
                showFlagInfos(data);
            }
        }
    });
    $.ajax(url +"/speeds",{
        dataType: 'JSON',
        data:params,
        success: function(data) {
            if (data.data) {
                showSpeedInfos(data.data);
            }
        }
    });
    //console.log(bbox);
    full_url = request_url + "?" + $.param(params);
    /*console.log(full_url);*/
    var source = map.getSource("perm");
    source.setData(full_url);
}

//Requests a server for envelope for wanted edge_id
//- moves map to this envelope,
//- enables bidirectional edges
//- highlights requested edge
function query_edge_id(edge_id) {
    console.info("Edge_ID:", edge_id);
    var params = {
        'edgeID': text.edge_id
    };
    //Enabling bidirectional edges since wanted edge can be reverse edge
    text.both = true;
    $.ajax(otp_config.hostname + "/query", {
            dataType: 'JSON',
            data: params,
            success: function(data) {
                if (data.data) {
                    console.info("Loaded speeds data:", data.data);
                    window.map.fitBounds([
                        [data.data.minX, data.data.minY],
                        [data.data.maxX, data.data.maxY]
                    ]);
                    ids = [parseInt(text.edge_id)];
                    // set the filter on the hover style layer to only select the features
                    // currently under the mouse
                    map.setFilter('perm-hover', [ 'all',
                                [ 'in', 'edge_id' ].concat(ids)
                    ]);
                } else {
                    alert("Problem getting edge id:\n" + data.errors);
                }
            }
    });
}
