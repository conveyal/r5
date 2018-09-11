package com.conveyal.r5.speed_test;

import com.conveyal.gtfs.model.Stop;
import com.conveyal.r5.profile.Path;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.StreetPath;
import com.conveyal.r5.speed_test.api.model.AgencyAndId;
import com.conveyal.r5.speed_test.api.model.Leg;
import com.conveyal.r5.speed_test.api.model.Place;
import com.conveyal.r5.speed_test.api.model.PolylineEncoder;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.RouteInfo;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;
import com.vividsolutions.jts.geom.Coordinate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;
import java.util.stream.Collectors;


class ItineraryMapper {
    private TransportNetwork transportNetwork;

    ItineraryMapper(TransportNetwork transportNetwork) {
        this.transportNetwork = transportNetwork;
    }

    SpeedTestItinerary createItinerary(ProfileRequest request, Path path, StreetPath accessPath, StreetPath egressPath) {
        SpeedTestItinerary itinerary = new SpeedTestItinerary();
        if (path == null) {
            return null;
        }

        // TODO TGR - Use request param here
        int MINIMUM_BOARD_WAIT_SEC = 60;

        itinerary.walkDistance = 0.0;
        itinerary.transitTime = 0;
        itinerary.waitingTime = 0;
        itinerary.weight = 0;

        int accessTime = accessPath.getDuration();
        int egressTime = egressPath.getDuration();

        List<Coordinate> acessCoords = accessPath.getEdges().stream()
                .map(t -> new Coordinate(accessPath.getEdge(t).getGeometry().getCoordinate().x, accessPath.getEdge(t).getGeometry().getCoordinate().y)).collect(Collectors.toList());
        List<Coordinate> egressCoords = egressPath.getEdges().stream()
                .map(t -> new Coordinate(egressPath.getEdge(t).getGeometry().getCoordinate().x, egressPath.getEdge(t).getGeometry().getCoordinate().y)).collect(Collectors.toList());
        Collections.reverse(egressCoords);

        // Access leg
        Leg accessLeg = new Leg();

        Stop firstStop = transportNetwork.transitLayer.stopForIndex.get(path.boardStops[0]);

        int accessLegEndTime = path.boardTimes[0] - MINIMUM_BOARD_WAIT_SEC;
        accessLeg.startTime = createCalendar(request.date,  accessLegEndTime - accessTime);
        accessLeg.endTime = createCalendar(request.date, accessLegEndTime);
        accessLeg.from = new Place(request.fromLon, request.fromLat, "Origin");
        accessLeg.from.stopIndex = -1;
        accessLeg.to = new Place(firstStop.stop_lat, firstStop.stop_lon, firstStop.stop_name);
        accessLeg.to.stopId = new AgencyAndId("RB", firstStop.stop_id);
        accessLeg.to.stopIndex = path.boardStops[0];
        accessLeg.mode = "WALK";
        accessLeg.legGeometry = PolylineEncoder.createEncodings(acessCoords);

        accessLeg.distance = distanceMMToMeters(accessPath.getDistance());

        itinerary.addLeg(accessLeg);

        for (int i = 0; i < path.patterns.length; i++) {
            Stop boardStop = transportNetwork.transitLayer.stopForIndex.get(path.boardStops[i]);
            Stop alightStop = transportNetwork.transitLayer.stopForIndex.get(path.alightStops[i]);

            // Transfer leg if present
            if (i > 0 && path.transferTimes[i] != -1) {
                Stop previousAlightStop = transportNetwork.transitLayer.stopForIndex.get(path.alightStops[i - 1]);
                StreetPath transferPath = getWalkLegCoordinates(path.alightStops[i - 1], path.boardStops[i]);
                List<Coordinate> transferCoords = transferPath.getEdges().stream()
                        .map(t -> new Coordinate(transferPath.getEdge(t).getGeometry().getCoordinate().x, transferPath
                                .getEdge(t).getGeometry().getCoordinate().y)).collect(Collectors.toList());

                Leg transferLeg = new Leg();
                transferLeg.startTime = createCalendar(request.date, path.alightTimes[i - 1]);
                transferLeg.endTime = createCalendar(request.date, path.transferTimes[i]);
                transferLeg.mode = "WALK";
                transferLeg.from = new Place(previousAlightStop.stop_lat, previousAlightStop.stop_lon, previousAlightStop.stop_name);
                transferLeg.to = new Place(boardStop.stop_lat, boardStop.stop_lon, boardStop.stop_name);
                transferLeg.legGeometry = PolylineEncoder.createEncodings(transferCoords);

                transferLeg.distance = distanceMMToMeters (transferPath.getDistance());

                itinerary.addLeg(transferLeg);
            }

            // Transit leg
            Leg transitLeg = new Leg();

            transitLeg.distance = 0.0;

            RouteInfo routeInfo = transportNetwork.transitLayer.routes
                    .get(transportNetwork.transitLayer.tripPatterns.get(path.patterns[i]).routeIndex);
            TripSchedule tripSchedule = transportNetwork.transitLayer.tripPatterns.get(path.patterns[i]).tripSchedules.get(path.trips[i]);
            TripPattern tripPattern = transportNetwork.transitLayer.tripPatterns.get(path.patterns[i]);

            itinerary.transitTime += path.alightTimes[i] - path.boardTimes[i];

            itinerary.waitingTime += path.boardTimes[i] - path.transferTimes[i];

            transitLeg.from = new Place(boardStop.stop_lat, boardStop.stop_lon, boardStop.stop_name);
            transitLeg.from.stopId = new AgencyAndId("RB", boardStop.stop_id);
            transitLeg.from.stopIndex = path.boardStops[i];

            transitLeg.to = new Place(alightStop.stop_lat, alightStop.stop_lon, alightStop.stop_name);
            transitLeg.to.stopId = new AgencyAndId("RB", alightStop.stop_id);
            transitLeg.to.stopIndex = path.alightStops[i];

            transitLeg.route = routeInfo.route_short_name;
            transitLeg.agencyName = routeInfo.agency_name;
            transitLeg.routeColor = routeInfo.color;
            transitLeg.tripShortName = tripSchedule.tripId;
            transitLeg.agencyId = routeInfo.agency_id;
            transitLeg.routeShortName = routeInfo.route_short_name;
            transitLeg.routeLongName = routeInfo.route_long_name;
            transitLeg.mode = TransitLayer.getTransitModes(routeInfo.route_type).toString();

            List<Coordinate> transitLegCoordinates = new ArrayList<>();
            boolean boarded = false;
            for (int j = 0; j < tripPattern.stops.length; j++) {
                if (!boarded && tripSchedule.departures[j] == path.boardTimes[i]) {
                    boarded = true;
                }
                if (boarded) {
                    transitLegCoordinates.add(new Coordinate(transportNetwork.transitLayer.stopForIndex.get(tripPattern.stops[j]).stop_lon,
                            transportNetwork.transitLayer.stopForIndex.get(tripPattern.stops[j]).stop_lat ));
                }
                if (boarded && tripSchedule.arrivals[j] == path.alightTimes[i]) {
                    break;
                }
            }

            transitLeg.legGeometry = PolylineEncoder.createEncodings(transitLegCoordinates);

            transitLeg.startTime = createCalendar(request.date, path.boardTimes[i]);
            transitLeg.endTime = createCalendar(request.date, path.alightTimes[i]);
            itinerary.addLeg(transitLeg);
        }

        // Egress leg
        Leg egressLeg = new Leg();
        Stop lastStop = transportNetwork.transitLayer.stopForIndex.get(path.alightStops[path.length - 1]);
        egressLeg.startTime = createCalendar(request.date, path.alightTimes[path.alightTimes.length - 1]);
        egressLeg.endTime = createCalendar(request.date, path.alightTimes[path.alightTimes.length - 1] + egressTime);
        egressLeg.from = new Place(lastStop.stop_lat, lastStop.stop_lon, lastStop.stop_name);
        egressLeg.from.stopIndex = path.alightStops[path.length - 1];
        egressLeg.from.stopId = new AgencyAndId("RB", lastStop.stop_id);
        egressLeg.to = new Place(request.toLon, request.toLat, "Destination");
        egressLeg.mode = "WALK";
        egressLeg.legGeometry = PolylineEncoder.createEncodings(egressCoords);

        egressLeg.distance = distanceMMToMeters (egressPath.getDistance());

        itinerary.addLeg(egressLeg);

        itinerary.startTime = accessLeg.startTime;
        itinerary.endTime = egressLeg.endTime;
        itinerary.duration = (itinerary.endTime.getTimeInMillis() - itinerary.startTime.getTimeInMillis())/1000;

        itinerary.transfers = path.patterns.length - 1;

        itinerary.initParetoVector();

        return itinerary;
    }

    private StreetPath getWalkLegCoordinates(int originStop, int destinationStop) {
        StreetRouter sr = new StreetRouter(transportNetwork.streetLayer);
        sr.profileRequest = new ProfileRequest();
        int originStreetVertex = transportNetwork.transitLayer.streetVertexForStop.get(originStop);
        sr.setOrigin(originStreetVertex);
        sr.quantityToMinimize = StreetRouter.State.RoutingVariable.DURATION_SECONDS;
        sr.distanceLimitMeters = 1000;
        sr.route();
        StreetRouter.State transferState = sr.getStateAtVertex(transportNetwork.transitLayer.streetVertexForStop
                .get(destinationStop));
        StreetPath transferPath = new StreetPath(transferState, transportNetwork, false);
        return transferPath;
    }

    private Calendar createCalendar(LocalDate date, int timeinSeconds) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("Europe/Oslo"));
        calendar.set(date.getYear(), date.getMonth().getValue(), date.getDayOfMonth()
                , 0, 0, 0);
        calendar.add(Calendar.SECOND, timeinSeconds);
        return calendar;
    }

    private double distanceMMToMeters(int distanceMm) {
        return (double) (distanceMm / 1000);
    }
}
