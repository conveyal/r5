var text = new FilterConfig();

function makeGUI() {
    "use strict";
    var gui = new dat.GUI();
    gui.add(text, 'debug_type', ['permissions', 'flags', 'speeds']).onFinishChange(getStyle);


    var allows_pedestrian = gui.addFolder('Allows Pedestrian');
    allows_pedestrian.add(text, 'show_allows_pedestrian')
    .onFinishChange(function(value) {flagChange('allows_pedestrian', value)});
    allows_pedestrian.addColor(text, 'color_allows_pedestrian')
    .onChange(function(color) {colorChange('allows_pedestrian', color)});

    var allows_bike = gui.addFolder('Allows Bike');
    allows_bike.add(text, 'show_allows_bike')
    .onFinishChange(function(value) {flagChange('allows_bike', value)});
    allows_bike.addColor(text, 'color_allows_bike')
    .onChange(function(color) {colorChange('allows_bike', color)});

    var allows_car = gui.addFolder('Allows Car');
    allows_car.add(text, 'show_allows_car')
    .onFinishChange(function(value) {flagChange('allows_car', value)});
    allows_car.addColor(text, 'color_allows_car')
    .onChange(function(color) {colorChange('allows_car', color)});

    var allows_wheelchair = gui.addFolder('Allows Wheelchair');
    allows_wheelchair.add(text, 'show_allows_wheelchair')
    .onFinishChange(function(value) {flagChange('allows_wheelchair', value)});
    allows_wheelchair.addColor(text, 'color_allows_wheelchair')
    .onChange(function(color) {colorChange('allows_wheelchair', color)});

    var bike_lts_1 = gui.addFolder('Bike Lts 1');
    bike_lts_1.add(text, 'show_bike_lts_1')
    .onFinishChange(function(value) {flagChange('bike_lts_1', value)});
    bike_lts_1.addColor(text, 'color_bike_lts_1')
    .onChange(function(color) {colorChange('bike_lts_1', color)});

    var bike_lts_2 = gui.addFolder('Bike Lts 2');
    bike_lts_2.add(text, 'show_bike_lts_2')
    .onFinishChange(function(value) {flagChange('bike_lts_2', value)});
    bike_lts_2.addColor(text, 'color_bike_lts_2')
    .onChange(function(color) {colorChange('bike_lts_2', color)});

    var bike_lts_3 = gui.addFolder('Bike Lts 3');
    bike_lts_3.add(text, 'show_bike_lts_3')
    .onFinishChange(function(value) {flagChange('bike_lts_3', value)});
    bike_lts_3.addColor(text, 'color_bike_lts_3')
    .onChange(function(color) {colorChange('bike_lts_3', color)});

    var bike_lts_4 = gui.addFolder('Bike Lts 4');
    bike_lts_4.add(text, 'show_bike_lts_4')
    .onFinishChange(function(value) {flagChange('bike_lts_4', value)});
    bike_lts_4.addColor(text, 'color_bike_lts_4')
    .onChange(function(color) {colorChange('bike_lts_4', color)});

    var unused = gui.addFolder('Unused');
    unused.add(text, 'show_unused')
    .onFinishChange(function(value) {flagChange('unused', value)});
    unused.addColor(text, 'color_unused')
    .onChange(function(color) {colorChange('unused', color)});

    var bike_path = gui.addFolder('Bike Path');
    bike_path.add(text, 'show_bike_path')
    .onFinishChange(function(value) {flagChange('bike_path', value)});
    bike_path.addColor(text, 'color_bike_path')
    .onChange(function(color) {colorChange('bike_path', color)});

    var sidewalk = gui.addFolder('Sidewalk');
    sidewalk.add(text, 'show_sidewalk')
    .onFinishChange(function(value) {flagChange('sidewalk', value)});
    sidewalk.addColor(text, 'color_sidewalk')
    .onChange(function(color) {colorChange('sidewalk', color)});

    var crossing = gui.addFolder('Crossing');
    crossing.add(text, 'show_crossing')
    .onFinishChange(function(value) {flagChange('crossing', value)});
    crossing.addColor(text, 'color_crossing')
    .onChange(function(color) {colorChange('crossing', color)});

    var roundabout = gui.addFolder('Roundabout');
    roundabout.add(text, 'show_roundabout')
    .onFinishChange(function(value) {flagChange('roundabout', value)});
    roundabout.addColor(text, 'color_roundabout')
    .onChange(function(color) {colorChange('roundabout', color)});

    var elevator = gui.addFolder('Elevator');
    elevator.add(text, 'show_elevator')
    .onFinishChange(function(value) {flagChange('elevator', value)});
    elevator.addColor(text, 'color_elevator')
    .onChange(function(color) {colorChange('elevator', color)});

    var stairs = gui.addFolder('Stairs');
    stairs.add(text, 'show_stairs')
    .onFinishChange(function(value) {flagChange('stairs', value)});
    stairs.addColor(text, 'color_stairs')
    .onChange(function(color) {colorChange('stairs', color)});

    var platform = gui.addFolder('Platform');
    platform.add(text, 'show_platform')
    .onFinishChange(function(value) {flagChange('platform', value)});
    platform.addColor(text, 'color_platform')
    .onChange(function(color) {colorChange('platform', color)});

    var bogus_name = gui.addFolder('Bogus Name');
    bogus_name.add(text, 'show_bogus_name')
    .onFinishChange(function(value) {flagChange('bogus_name', value)});
    bogus_name.addColor(text, 'color_bogus_name')
    .onChange(function(color) {colorChange('bogus_name', color)});

    var no_thru_traffic = gui.addFolder('No Thru Traffic');
    no_thru_traffic.add(text, 'show_no_thru_traffic')
    .onFinishChange(function(value) {flagChange('no_thru_traffic', value)});
    no_thru_traffic.addColor(text, 'color_no_thru_traffic')
    .onChange(function(color) {colorChange('no_thru_traffic', color)});

    var slope_override = gui.addFolder('Slope Override');
    slope_override.add(text, 'show_slope_override')
    .onFinishChange(function(value) {flagChange('slope_override', value)});
    slope_override.addColor(text, 'color_slope_override')
    .onChange(function(color) {colorChange('slope_override', color)});

    var transit_link = gui.addFolder('Transit Link');
    transit_link.add(text, 'show_transit_link')
    .onFinishChange(function(value) {flagChange('transit_link', value)});
    transit_link.addColor(text, 'color_transit_link')
    .onChange(function(color) {colorChange('transit_link', color)});
}
