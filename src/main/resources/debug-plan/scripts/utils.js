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
Function enables or disables layer depending on true or false gui value
**/
function flagChange(flag_name, value) {
    if (current_type != "flags") {
        return;
    }
    //console.log("Flag:", flag_name);
    //console.log("Info:", value);
    if (value == false) {
        map.removeLayer("perm-"+flag_name);
    } else {
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
        map.addLayer(flag_layer);
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

//Switches Mapbox GL style between permissions and viewing specific flags
function getStyle(type) {
    console.info(type);
    console.log(text);
    style = clone(mapbox_style);
    style.sources["perm"].data = full_url;
    if (type == "permissions") {
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
    if (text.both) {
        style.layers.push(oneway_icons_style);
        /*console.log("Added oneway icons");*/
    }
    console.log(style);
    map.setStyle(style);
    current_type = type;
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
            pop += prop[layer_info[i]];
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
