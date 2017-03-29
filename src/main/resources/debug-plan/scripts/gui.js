var text = new FilterConfig();

function makeGUI() {
    "use strict";
    var gui = new dat.GUI();
    gui.remember(text);
    gui.add(text, 'debug_type', ['permissions', 'flags', 'speeds', 'turns']).onFinishChange(getStyle);
    gui.add(text, "unit", ['kmh', 'mph']);
    gui.add(text, 'both').listen().name("Show bidirectional").onChange(function(value) { updateMap(); });
    gui.add(text, "edge_id").onFinishChange(query_edge_id);
    var gui_speed = gui.addFolder("Speeds")
    gui_speed.addColor(text, 'min_speed_color').onChange(function(color) { colorChange("min", color);});
    gui_speed.addColor(text, 'middle_speed_color').onChange(function(color) { colorChange("mid", color);});
    gui_speed.addColor(text, 'max_speed_color').onChange(function(color) { colorChange("max", color);});

    $.ajax(url + "/stats", {
        dataType: 'JSON',
        success: function(data) {
            if (data.data) {
                //Adds fake oneway flag
                data.data["ONEWAY"] = "1";
                var next_color = 0;
                $.each(data.data, function(key, usages) {
                    var split_name = key.replace("_", " ");
                    var nice_name = split_name.toProperCase();
                    var lowercase_name = key.toLowerCase()
                    text['show_'+lowercase_name] = false;
                    text['color_'+lowercase_name] = colors[next_color];
                    text['dash_'+lowercase_name] = false;
                    next_color++;
                    /*console.log("key: ", split_name.toProperCase());*/
                    var tmp = gui.addFolder(nice_name);
                    tmp.add(text, 'show_' + lowercase_name)
                    .onFinishChange(function(value) {flagChange(lowercase_name, value)});
                    tmp.addColor(text, 'color_' + lowercase_name)
                    .onChange(function(color) {colorChange(lowercase_name, color)});
                    tmp.add(text, 'dash_' + lowercase_name)
                    .onFinishChange(function(value) {dashChange(lowercase_name, value);});

                });

                showFlagInfos(data);
                showSpeedInfos(speeds);
            }else {
                console.error(data.errors);
                alert("Problem loading flags from server:" + data.errors);
            }
        }
    });


}
