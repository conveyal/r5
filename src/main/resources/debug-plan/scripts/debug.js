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
            //console.log(e.point, features);
            var ids = features.map(function (feat) { return feat.properties.edge_id });
            if (ids.length == 1) {
                var pop_html = fillPopup(features[0], current_layer);
                if (pop_html != null) {
                    var tooltip = new mapboxgl.Popup()
                    .setLngLat(e.lngLat)
                    .setHTML(pop_html)
                    .addTo(map);
                }
            }


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
