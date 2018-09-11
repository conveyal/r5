package com.conveyal.r5.speed_test;

import com.conveyal.gtfs.model.Stop;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.StreetPath;
import com.conveyal.r5.profile.mcrr.api.Path2;
import com.conveyal.r5.profile.mcrr.api.PathLeg;
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

public class ItineraryMapper2 {
    private TransportNetwork transportNetwork;

    public ItineraryMapper2(TransportNetwork transportNetwork) {
        this.transportNetwork = transportNetwork;
    }

    SpeedTestItinerary createItinerary(ProfileRequest request, Path2 path, StreetPath accessPath, StreetPath egressPath) {
        SpeedTestItinerary itinerary = new SpeedTestItinerary();
        if (path == null) {
            return null;
        }

        itinerary.walkDistance = 0.0;
        itinerary.transitTime = 0;
        itinerary.waitingTime = 0;
        itinerary.weight = 0;

        int numberOfTransits = 0;
        int accessTime = accessPath.getDuration();
        int egressTime = egressPath.getDuration();

        List<Coordinate> acessCoords = accessPath.getEdges().stream()
                .map(t -> new Coordinate(accessPath.getEdge(t).getGeometry().getCoordinate().x, accessPath.getEdge(t).getGeometry().getCoordinate().y)).collect(Collectors.toList());
        List<Coordinate> egressCoords = egressPath.getEdges().stream()
                .map(t -> new Coordinate(egressPath.getEdge(t).getGeometry().getCoordinate().x, egressPath.getEdge(t).getGeometry().getCoordinate().y)).collect(Collectors.toList());
        Collections.reverse(egressCoords);

        // Access leg
        Leg leg = new Leg();
        PathLeg accessLeg = path.accessLeg();

        Stop firstStop = transitLayer().stopForIndex.get(accessLeg.toStop());

        leg.startTime = createCalendar(request.date, accessLeg.fromTime());
        leg.endTime = createCalendar(request.date, accessLeg.toTime());
        leg.from = new Place(request.fromLon, request.fromLat, "Origin");
        leg.from.stopIndex = -1;
        leg.to = new Place(firstStop.stop_lat, firstStop.stop_lon, firstStop.stop_name);
        leg.to.stopId = new AgencyAndId("RB", firstStop.stop_id);
        leg.to.stopIndex = accessLeg.toStop();
        leg.mode = "WALK";
        leg.legGeometry = PolylineEncoder.createEncodings(acessCoords);
        leg.distance = distanceMMToMeters(accessPath.getDistance());

        itinerary.addLeg(leg);

        PathLeg prevPathLeg = accessLeg;

        for (PathLeg pathLeg :  path.legs()) {
            Stop fromStop = transitLayer().stopForIndex.get(pathLeg.fromStop());
            Stop toStop = transitLayer().stopForIndex.get(pathLeg.toStop());
            leg = new Leg();

            // Transfer leg if present
            if (pathLeg.isTransfer()) {

                StreetPath transferPath = getWalkLegCoordinates(pathLeg.fromStop(), pathLeg.toStop());
                List<Coordinate> transferCoords = transferPath.getEdges().stream()
                        .map(t -> new Coordinate(transferPath.getEdge(t).getGeometry().getCoordinate().x, transferPath
                                .getEdge(t).getGeometry().getCoordinate().y)).collect(Collectors.toList());

                leg.startTime = createCalendar(request.date, pathLeg.fromTime());
                leg.endTime = createCalendar(request.date, pathLeg.toTime());
                leg.mode = "WALK";
                leg.from = new Place(fromStop.stop_lat, fromStop.stop_lon, fromStop.stop_name);
                leg.to = new Place(toStop.stop_lat, toStop.stop_lon, toStop.stop_name);
                leg.legGeometry = PolylineEncoder.createEncodings(transferCoords);

                leg.distance = distanceMMToMeters (transferPath.getDistance());

            }
            else {
                // Transit leg
                ++numberOfTransits;
                leg.distance = 0.0;

                TripPattern tripPattern = transitLayer().tripPatterns.get(pathLeg.pattern());
                RouteInfo routeInfo = transitLayer().routes.get(tripPattern.routeIndex);
                TripSchedule tripSchedule = tripPattern.tripSchedules.get(pathLeg.trip());

                itinerary.transitTime += pathLeg.toTime() - pathLeg.fromTime();

                itinerary.waitingTime += pathLeg.fromTime() - prevPathLeg.toTime();

                leg.from = new Place(fromStop.stop_lat, fromStop.stop_lon, fromStop.stop_name);
                leg.from.stopId = new AgencyAndId("RB", fromStop.stop_id);
                leg.from.stopIndex = pathLeg.fromStop();

                leg.to = new Place(toStop.stop_lat, toStop.stop_lon, toStop.stop_name);
                leg.to.stopId = new AgencyAndId("RB", toStop.stop_id);
                leg.to.stopIndex = pathLeg.toStop();

                leg.route = routeInfo.route_short_name;
                leg.agencyName = routeInfo.agency_name;
                leg.routeColor = routeInfo.color;
                leg.tripShortName = tripSchedule.tripId;
                leg.agencyId = routeInfo.agency_id;
                leg.routeShortName = routeInfo.route_short_name;
                leg.routeLongName = routeInfo.route_long_name;
                leg.mode = TransitLayer.getTransitModes(routeInfo.route_type).toString();

                List<Coordinate> transitLegCoordinates = new ArrayList<>();
                boolean boarded = false;
                for (int j = 0; j < tripPattern.stops.length; j++) {
                    if (!boarded && tripSchedule.departures[j] == pathLeg.fromTime()) {
                        boarded = true;
                    }
                    if (boarded) {
                        transitLegCoordinates.add(new Coordinate(transitLayer().stopForIndex.get(tripPattern.stops[j]).stop_lon,
                                transitLayer().stopForIndex.get(tripPattern.stops[j]).stop_lat));
                    }
                    if (boarded && tripSchedule.arrivals[j] == pathLeg.toTime()) {
                        break;
                    }
                }

                leg.legGeometry = PolylineEncoder.createEncodings(transitLegCoordinates);

                leg.startTime = createCalendar(request.date, pathLeg.fromTime());
                leg.endTime = createCalendar(request.date, pathLeg.toTime());
            }
            itinerary.addLeg(leg);
            prevPathLeg = pathLeg;
        }

        // Egress leg
        leg = new Leg();
        PathLeg egressLeg = path.egressLeg();

        Stop lastStop = transitLayer().stopForIndex.get(egressLeg.fromStop());
        leg.startTime = createCalendar(request.date, egressLeg.fromTime());
        leg.endTime = createCalendar(request.date, egressLeg.fromTime() + egressTime);
        leg.from = new Place(lastStop.stop_lat, lastStop.stop_lon, lastStop.stop_name);
        leg.from.stopIndex = egressLeg.fromStop();
        leg.from.stopId = new AgencyAndId("RB", lastStop.stop_id);
        leg.to = new Place(request.toLon, request.toLat, "Destination");
        leg.mode = "WALK";
        leg.legGeometry = PolylineEncoder.createEncodings(egressCoords);

        leg.distance = distanceMMToMeters (egressPath.getDistance());

        itinerary.addLeg(leg);

        itinerary.startTime = itinerary.legs.get(0).startTime;
        itinerary.endTime = leg.endTime;
        itinerary.duration = (itinerary.endTime.getTimeInMillis() - itinerary.startTime.getTimeInMillis())/1000;

        // The number of transfers is the number of transits minus one, we can NOT count the number of Transfers
        // in the path or itinerary, because transfers at the same stop does not produce a transfer object, just two
        // transits following each other.
        itinerary.transfers = numberOfTransits-1;

        itinerary.initParetoVector();

        return itinerary;
    }

    private TransitLayer transitLayer() {
        return transportNetwork.transitLayer;
    }

    private StreetPath getWalkLegCoordinates(int originStop, int destinationStop) {
        StreetRouter sr = new StreetRouter(transportNetwork.streetLayer);
        sr.profileRequest = new ProfileRequest();
        int originStreetVertex = transitLayer().streetVertexForStop.get(originStop);
        sr.setOrigin(originStreetVertex);
        sr.quantityToMinimize = StreetRouter.State.RoutingVariable.DURATION_SECONDS;
        sr.distanceLimitMeters = 1000;
        sr.route();
        StreetRouter.State transferState = sr.getStateAtVertex(transitLayer().streetVertexForStop
                .get(destinationStop));
        return new StreetPath(transferState, transportNetwork, false);
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
