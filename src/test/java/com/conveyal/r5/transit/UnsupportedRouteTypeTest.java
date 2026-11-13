package com.conveyal.r5.transit;

import com.conveyal.r5.OneOriginResult;
import com.conveyal.r5.analyst.TravelTimeComputer;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.conveyal.r5.analyst.fare.SimpleInRoutingFareCalculator;
import com.conveyal.r5.analyst.network.Distribution;
import com.conveyal.r5.analyst.network.GridLayout;
import com.conveyal.r5.analyst.network.GridRoute;
import com.conveyal.r5.api.util.TransitModes;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.EnumSet;

import static com.conveyal.r5.analyst.network.SimpsonDesertTests.SIMPSON_DESERT_CORNER;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GTFS feeds using extended route types (e.g. the Swiss national feed) can contain route_type codes with no
 * corresponding TransitModes, such as TPEG taxi services (1500-1599). Routes with these types can never match the
 * transit modes of a request, so they should be ignored during routing rather than causing every request on the
 * network to fail. See https://github.com/conveyal/r5/issues/1001
 */
public class UnsupportedRouteTypeTest {

    /** A route_type in the TPEG taxi service range, which R5 does not route on. */
    private static final int TAXI_ROUTE_TYPE = 1501;

    @Test
    public void unsupportedRouteTypesAreStillRejectedByGetTransitModes () {
        assertThrows(IllegalArgumentException.class, () -> TransitLayer.getTransitModes(TAXI_ROUTE_TYPE));
        assertThrows(IllegalArgumentException.class, () -> TransitLayer.getTransitModes(1700));
        assertThrows(IllegalArgumentException.class, () -> TransitLayer.getTransitModes(8));
        assertEquals(TransitModes.BUS, TransitLayer.getTransitModes(3));
        assertEquals(TransitModes.FUNICULAR, TransitLayer.getTransitModes(1400));
    }

    @Test
    public void getTransitModesOrNullReturnsNullForUnsupportedRouteTypes () {
        assertNull(TransitLayer.getTransitModesOrNull(TAXI_ROUTE_TYPE));
        assertNull(TransitLayer.getTransitModesOrNull(1599));
        assertNull(TransitLayer.getTransitModesOrNull(1700));
        assertNull(TransitLayer.getTransitModesOrNull(8));
        assertNull(TransitLayer.getTransitModesOrNull(-1));
        // Supported types map exactly as they do in getTransitModes.
        for (int routeType : new int[] {0, 1, 2, 3, 4, 5, 6, 7, 11, 12, 100, 200, 300, 500, 700, 900, 1000, 1100, 1200,
                1300, 1400, 1499}) {
            assertEquals(TransitLayer.getTransitModes(routeType), TransitLayer.getTransitModesOrNull(routeType));
        }
    }

    /**
     * Pattern filtering must not throw on a pattern with an unsupported route type, and must exclude that pattern
     * even when all transit modes are requested, while keeping patterns of other routes.
     */
    @Test
    public void filteredPatternsSkipUnsupportedRouteTypes () throws Exception {
        GridLayout gridLayout = new GridLayout(SIMPSON_DESERT_CORNER, 100);
        GridRoute railRoute = gridLayout.addHorizontalRoute(20, 20);
        GridRoute taxiRoute = gridLayout.addHorizontalRoute(60, 20);
        TransportNetwork network = gridLayout.generateNetwork();
        TransitLayer transitLayer = network.transitLayer;
        setRouteType(transitLayer, taxiRoute, TAXI_ROUTE_TYPE);

        BitSet allServices = new BitSet();
        allServices.set(0, transitLayer.services.size());
        FilteredPatterns filtered = new FilteredPatterns(
                transitLayer, EnumSet.allOf(TransitModes.class), allServices
        );
        int railPatterns = 0;
        for (int p = 0; p < transitLayer.tripPatterns.size(); p++) {
            RouteInfo route = transitLayer.routes.get(transitLayer.tripPatterns.get(p).routeIndex);
            if (route.route_type == TAXI_ROUTE_TYPE) {
                assertNull(filtered.patterns.get(p), "Pattern with unsupported route_type should be filtered out.");
            } else {
                assertNotNull(filtered.patterns.get(p), "Pattern with supported route_type should be retained.");
                railPatterns++;
            }
        }
        // The rail route is bidirectional, so it has two patterns.
        assertEquals(2, railPatterns);
    }

    /**
     * Reproduces https://github.com/conveyal/r5/issues/1001: a single route with an unsupported route_type used to
     * make every travel time computation on the network fail, even when that route was irrelevant to the trip and
     * its mode was not requested. Travel times should instead match those of the same network without that route.
     * The layout and expected distribution are the same as SimpsonDesertTests#testGridScheduled, whose third
     * horizontal route does not contribute to travel times between this origin and destination.
     */
    @Test
    public void travelTimesIgnoreRoutesWithUnsupportedRouteTypes () throws Exception {
        GridLayout gridLayout = new GridLayout(SIMPSON_DESERT_CORNER, 100);
        gridLayout.addHorizontalRoute(20, 20);
        gridLayout.addHorizontalRoute(40, 20);
        GridRoute taxiRoute = gridLayout.addHorizontalRoute(60, 20);
        gridLayout.addVerticalRoute(40, 20);
        TransportNetwork network = gridLayout.generateNetwork();
        setRouteType(network.transitLayer, taxiRoute, TAXI_ROUTE_TYPE);

        RegionalTask task = gridLayout.newTaskBuilder()
                .weekdayMorningPeak()
                .setOrigin(20, 20)
                .singleFreeformDestination(40, 40)
                .buildRegional();
        // As in the original report, request only the mode actually served by the other routes (GTFS route_type 2).
        task.transitModes = EnumSet.of(TransitModes.RAIL);

        OneOriginResult oneOriginResult = new TravelTimeComputer(task, network).computeTravelTimes();
        Distribution expected = new Distribution(31, 20).delay(1);
        expected.multiAssertSimilar(oneOriginResult.travelTimes, 0);
    }

    /**
     * Routing with an in-routing fare calculator uses McRaptorSuboptimalPathProfileRouter instead of FastRaptorWorker,
     * which filters patterns by mode separately. It should also skip routes with unsupported route types, producing the
     * same travel times as when that (non-contributing) route has an ordinary route type.
     */
    @Test
    public void faresRoutingIgnoresRoutesWithUnsupportedRouteTypes () throws Exception {
        GridLayout gridLayout = new GridLayout(SIMPSON_DESERT_CORNER, 100);
        gridLayout.addHorizontalRoute(20, 20);
        gridLayout.addHorizontalRoute(40, 20);
        GridRoute otherRoute = gridLayout.addHorizontalRoute(60, 20);
        gridLayout.addVerticalRoute(40, 20);
        TransportNetwork network = gridLayout.generateNetwork();

        int[][] railTravelTimes = computeTravelTimesWithFares(gridLayout, network);
        assertTrue(railTravelTimes[0][0] < 120, "Destination should be reached by transit.");

        setRouteType(network.transitLayer, otherRoute, TAXI_ROUTE_TYPE);
        int[][] taxiTravelTimes = computeTravelTimesWithFares(gridLayout, network);
        assertArrayEquals(railTravelTimes, taxiTravelTimes);
    }

    private static int[][] computeTravelTimesWithFares (GridLayout gridLayout, TransportNetwork network) {
        RegionalTask task = gridLayout.newTaskBuilder()
                .weekdayMorningPeak()
                .setOrigin(20, 20)
                .singleFreeformDestination(40, 40)
                // McRaptor samples one departure time per draw. Its seed is derived from the origin, so is repeatable.
                .monteCarloDraws(20)
                .buildRegional();
        task.transitModes = EnumSet.of(TransitModes.RAIL);
        SimpleInRoutingFareCalculator fareCalculator = new SimpleInRoutingFareCalculator();
        fareCalculator.fare = 100;
        task.inRoutingFareCalculator = fareCalculator;
        task.maxFare = 1000;
        return new TravelTimeComputer(task, network).computeTravelTimes().travelTimes.getValues();
    }

    private static void setRouteType (TransitLayer transitLayer, GridRoute gridRoute, int routeType) {
        int nChanged = 0;
        for (RouteInfo route : transitLayer.routes) {
            if (route.route_id.equals(gridRoute.id) || route.route_id.endsWith(":" + gridRoute.id)) {
                route.route_type = routeType;
                nChanged++;
            }
        }
        assertEquals(1, nChanged, "Expected exactly one route to match grid route " + gridRoute.id);
    }

}
