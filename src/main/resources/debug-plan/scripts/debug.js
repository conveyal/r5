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
            ]);
        }
    });
    makeGUI();
    //request new geojson on map move
    map.on('moveend', function() {
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
        $.ajax(url +"/stats",{
            dataType: 'JSON',
            data:params,
            success: function(data) {
                if (data.data) {
                    showFlagInfos(data);
                }
            }
        });
        //console.log(bbox);
        full_url = request_url + "?" + $.param(params);
        /*console.log(full_url);*/
        var source = map.getSource("perm");
        source.setData(full_url);
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
