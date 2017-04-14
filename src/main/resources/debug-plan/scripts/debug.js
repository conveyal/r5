/*
map.on('style.load', function() {
	map.addSource('perm', source);
});*/

$(function() {
    //Sets map based on graph data from server
    $.ajax(otp_config.hostname+"/metadata", {
        dataType: 'JSON',
        success: function(data) {
            window.map.fitBounds([
                [data.envelope.minX, data.envelope.minY],
                [data.envelope.maxX, data.envelope.maxY]
            ],
            {speed:100
            });
        }
    });
    makeGUI();
    //request new geojson on map move
    map.on('moveend', function() {
        updateMap();
    });

    //shows hower tag on map and shows a popup
    map.on('click', function(e) {
        // query the map for the under the mouse
        map.featuresAt(e.point, { radius: 5, includeGeometry: true }, function (err, features) {
            if (err) throw err;
            var seen_ids = Object.create(null);
            var pop_html = "";
            for(var i=0; i < features.length; i++) {
                var feature = features[i];
                if (!(feature.properties.edge_id in seen_ids)) {
                    pop_html += fillPopup(feature, current_layer);
                    seen_ids[feature.properties.edge_id] = true;
                }
            }
            if (pop_html.length > 10) {
                var tooltip = new mapboxgl.Popup()
                .setLngLat(e.lngLat)
                .setHTML(pop_html)
                .addTo(map);
            }
            var ids = Object.keys(seen_ids).map(function (string_id) { return parseInt(string_id); });

            // set the filter on the hover style layer to only select the features
            // currently under the mouse
            map.setFilter('perm-hover', [ 'all',
                        [ 'in', 'edge_id' ].concat(ids)
            ]);
        });
    });

    //disables map rotation
    map.dragRotate.disable();
    getStyle(current_type);
});
