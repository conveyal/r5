# GTFS Fares V2 support

[GTFS-Fares V2](https://bit.ly/gtfs-fares) is a proposed standard to incorporate more complex fare scenarios into GTFS than are supported by the current `fare_rules.txt` and `fare_attributes.txt` system, which cannot represent fares in many places. GTFS-Fares V2 still cannot represent all fare systems, though extensions to the specification are regular. Conveyal Analysis currently supports a subset of GTFS-Fares V2, which is described in this page. This subset is used to provide fare support in Toronto; as applications of fare routing continue, this fare calculator should be extended. GTFS Fares v2 routing can be requested by setting the InRoutingFareCalculator type to `fares-v2` in the profile request.

## Fare structure

Several files are used to specify the fare structure within GTFS Fares.

### `fare_leg_rules`

Fare leg rules specify the fare for a single leg of a journey (except in the case of as_route fare networks, described below). The fare leg rule for a leg is found based on the `fare_network_id`, `from_area_id` and `to_area_id` in the current implementation. The matching fare leg rule with the lowest `order` is used, and `is_symmetrical` is supported to allow trips in either direction. `amount` and `currency` are used to define the cost. Other fields are not supported, and will either cause an error or be ignored. If multiple rules match with the same order, one is selected; which one is undefined. `from_area_id` and `to_area_id` can be blank (wildcard match), an area ID (defined below), or a stop_id. `fare_network_id` can be either a fare network ID or a route ID.

`fare_leg_id` should be used when a leg is referred to in `fare_transfer_rules`. `amount` must be specified; `min_amount` and `max_amount` are not supported.

Multiple rules are allowed to have the same `order` in GTFS-Fares v2, in which case the user is allowed to choose a fare. This is not supported in Conveyal Analysis. If multiple rules that apply to the same leg have the same order, one of them will be used; which one is undefined.

### `fare_transfer_rules`

Fare transfer rules define discounted transfers between fare legs. Currently, Conveyal Analysis only considers the from and to leg when evaluating a transfer; that is, the second transfer in a rail -> bus -> bus trip is treated the same as the transfer in a bus -> bus trip. Fares v2 does contain a field `spanning_limit` but this is insufficient for representing complex transfer systems where [the number of transfers allowed depends on the services involved](../newyork.md#staten-island-railway).

Currently, the fields `from_leg_group_id` and `to_leg_group_id` are used to match fare transfer rules to the `fare_leg_rules` that were matched for each leg. Other fields are not used in matching. Duration limits are not currently supported, although in most accessibility analyses these limits are non-binding. `fare_transfer_type` can be either 0 ("the cost of the sub-journey is the cost of the first leg PLUS the cost in the amount field"), in which case amount would be expected to be positive, or 1 ("the cost of the sub-journey is the sum of the cost PLUS the cost in the amount field"), in which case amount would be expected to be negative. 3 ("the cost of the sub-journey is the cost of the most expensive leg of the sub-journey PLUS the cost in the amount field.") is not currently supported, although adding support for it would not be difficult.

### `fare_areas`

Fare areas are ways to group stops for fare purposes. Currently, members of fare areas can only be specified as `stop_id`s; `trip_id` + `stop_sequence` is not currently supported.

### `fare_networks`

Fare networks are used to group routes together to make specifying fares more concise. They can be used to refer to groups of routes in `fare_leg_rules`. Moreover, a fare network can have `as_route` set to 1, in which case any set of journeys within the network is matched as a single journey (for instance, subway systems where fare is based on where you entered and left the system, not what you did in between). Currently there is discussion on how to code the situation where someone leaves the paid area and reboards—for example, by making an on-street transfer from the Blue Line at Bowdoin to the Red Line at Charles-MGH in Boston.

More complex fare networks where the total fare depends not only on where you boarded and alighted, but also which zones you passed through (for instance, Long Island Rail Road, London Underground, or GO in Toronto) are not currently supported; there is discussion of extending `contains_area_id` to handle these cases. See below for a specific workaround for GO in Toronto.

`as_route` fare networks are supported in Conveyal Analysis. When calculating the fare for a ride on a vehicle in an `as_route` network, we peek forward to see if the next ride is a member of any of the same `as_route` networks (a route can be a member of multiple overlapping networks). This process is repeated until we get to a ride that does not share any common networks with all of the previous `as_route` rides.

When the last ride in a trip is in an `as_route` network, information about the ride is included in the transfer allowance to make sure it is not pruned in favor of a trip with different `as_route` transfer privileges; this is described below.

## Handling multiple feeds

GTFS-Fares v2 does not allow for interfeed transfer discounts. Conveyal Analysis does not support multiple GTFS-Fares v2 feeds in the same network, regardless of whether interfeed discounts exist or not; feeds should be merged if fare support is desired.

## Algorithm implementation

The algorithm consists of two parts: the fare calculation engine, which calculates fares for _bona fide_ trips where we know all the rides, and a transfer allowance that keeps track of potential discounts that could be realized in the future due to having taken a particular fare in the past.

## Fare calculator

The fare calculator for GTFS Fares V2 is a bit different from and shorter than other fare calculators because it does not have a lot of city-specific logic. It loops through all of the all of the rides in a journey and performs the following steps:

1. Peek ahead to see if the next ride can be merged with this one through an `as_route` fare network.
    First, identify the `as_route` fare networks this ride is a part of, then for each following rides
    a.  Identify the `as_route` fare networks the following ride is a part of
    b.  AND this with the fare networks already identified
    c.  If there are common `as_route` fare networks:
        i.  Move the alight stop and alight time for the combined leg to this stop and alight time
        ii.  Advance the outer loop counter
    This leaves us with a pseudo-ride that stretches from the first board stop to the last alight stop in an `as_route` network. This greedy matching means that if there are three rides, the first on a route in network A, the second on a route in networks A and B, and the final on a route in network B only, the first two legs will be merged and the third will be treated as a new leg, even if merging the second two legs and treating the first separately would be advantageous. This situation of overlapping networks is believed to be rare.
2. A fare leg rule rule for the ride is found based on the fare networks the ride is in, and the board and alight stops. The rule with the lowest order that matches the networks, from stop, and to stop (either explicitly or via a blank/wildcard field) is returned. If there are multiple such rules, which one is returned is undefined.
3. If this is not the first ride, a transfer rule is searched for
4. If this is the first ride, or no transfer rule is found, the fare from the leg rule is added to the cumulative fare for the journey.

## Transfer allowance definition

A key component of the algorithm for finding low-cost paths in transit networks described by [Conway and Stewart 2019](https://files.indicatrix.org/Conway-Stewart-2019-Charlie-Fare-Constraints.pdf) is the "transfer allowance," which represents all of the potential discounts that could be realized by having used a particular journey suffix. In the current GTFS Fares v2 implementation, the transfer allowance is based entirely on the fare leg rule you traversed most recently; since `spanning_limit` is not implemented and fare transfer rules can only refer to two legs, the transfer privileges are the same regardless of what you rode 2, 3, etc. rides ago.

For rides that are not in `as_route` fare networks, the only thing in the transfer allowance is the index of the last `fare_leg_group`. In the Fareto interface, the `fare_leg_group` will display as a string, but internally they are represented as integers for fast equality checks. Transfer allowances that have the same most recent fare leg group are considered comparable, those that do not are not.

This seems to perform just fine in Toronto, but it will not perform well in systems that are coded with many leg rules for fares with equivalent transfer privileges. In the future, more efficiency might be gained by actually having some representation of the theoretical concept of transfer allowance—that is, a vector of the discounts on all possible journey suffixes. Then a fare with better transfer privileges could kick out one with the same or worse, even if they didn't have the same most recent fare leg rule. This could even be precomputed at network build time to create a list of what fare leg rules have better transfer privileges than other fare leg rules.

For `fare_leg_rules` that _are_ in `as_route` fare networks, the transfer allowance additional contains an array of _which_ `as_route` networks they are in, and where they boarded. These must be equal for domination to occur. This will overretain trips, but `as_route` networks tend to be small (e.g. commuter rail systems or subways) so this is immaterial.

### Max transfer allowance value

The maximum transfer allowance value is not computed, but rather is hard-wired at 10,000,000 CAD. This is presumably more expensive than the most expensive trip on any transit system, meaning that no trips will be eliminated by Theorem 3.1 in [the paper](https://files.indicatrix.org/Conway-Stewart-2019-Charlie-Fare-Constraints.pdf). Routing is still correct, because this will overretain trips. The maximum transfer allowance when it is defined is the maximum discount you could get off any future journey suffix, but there is no guarantee that that journey suffix will actually be taken. You can think of the very high maximum transfer allowance as being a transfer to a "ghost" train that is normally very expensive, but heavily discounted with the fare paid so far, but that does not connect to any destinations.

## City-specific extensions

At least as of this writing it is not possible to represent the complexities of all cities in GTFS-Fares v2, but it does come close for many cities. This section documents city-specific extensions that can be enabled through properties of the in-routing fare calculator.

### Toronto

#### Extension to properly model GO fares

GO fares in Toronto as implemented as an `as_route` fare network, since the fare is based on the zones you travel through. However, in some cases, it may be optimal to travel beyond your origin or destination zones, change trains/buses, and double back. For instance, consider [the second option for trip from Union Station to the Thornhill neighborhood](https://projects.indicatrix.org/fareto-examples/?load=broken-yyz-downtown-to-york). The GO fare for the origin and destination stations for the full trip is $6.80, but you have to actually travel beyond the destination station, to Unionville, to transfer, so the correct fare is actually $7.80—and the [fare calculator on the GO website](https://www.gotransit.com/en/trip-planning/calculate-fare/your-fare) reflects this when you select a transfer station of Unionville. I think this is because [the fare bylaw, on page 1 of the appendix](https://www.gotransit.com/static_files/gotransit/assets/pdf/Policies/By-Law_No2A.pdf) says that "This Tariff of Fares sets out the base fares applicable for a single one-way ride on the transit system _within_ the enumerated zones, including all applicable taxes" (emphasis mine). So a trip from Union Station to Thornhill via Unionville is _within_ the zones from Union Station to Unionville.

To support this in GTFS Fares v2, it would have to be possible to specify multiple `contains_area_ids` for each fare. Since that is not possible, a workaround is implemented in the fare calculator. When `useAllStopsWhenCalculatingAsRouteFareNetwork` is set to true, rather than only search for fare rules that apply to the origin and destination stops of the whole journey, we search for fare leg rules matching from_area_ids of _any_ stop within the joined as_route trips except the final alight stop, and to_area_ids of _any_ stop except the first board stop. It is not only board stops considered for from_area_ids and alight stops considered for to_area_ids, because you might do a trip C - A walk to B - D, and this should cost the A-D fare even though you didn't ever board at A. When this switch is enabled, [the router finds the correct fare for the example trip above](https://projects.indicatrix.org/fareto-examples/?load=fixed-yyz-downtown-to-york) (some options no longer appear because I disabled usage of subways in this example so that the now-more-expensive GO trip would not be above the Pareto curve).

The way this is implemented in the router is that when the as_route legs are compressed to a single leg, the `fare_leg_rule`s for each from stop ID are OR'ed together, the `fare_leg_rule`s for each to stop ID are similarly OR'ed together, and the results of those operations are AND'ed together to get all possible fare rules. The one with the lowest order is then used. This requires that the orders in the GTFS be set such that the most extensive `fare_leg_rule` have the lowest order.

This also requires some changes to the transfer allowance, because two journeys that start at the same stop but transfer at different stops might have different fares. So an array of all the lowest-order fare leg rules for the journey within the `as_route` network thus far is added to the transfer allowance, and transfer allowances are considered comparable iff they have the same lowest-order fare rules. As long as (1) the most extensive fare rule is among the lowest-order fare rules, and (2) there are no more extensive fare rules among the lowest order fare rules, two journeys with the same lowest-order fare rules have the same extents.

_Proof_: Suppose without loss of generality that fare leg rule 1 is the most extensive ("full extent") for journey Q, and R.potentialAsRouteFareLegRules == Q.potentialAsRouteFareRules. 
1. By condition 1, if 1 is the most extensive fare leg rules for journey Q, then it must appear in Q.potentialAsRouteFareRules.
2. By condition 2, no other more extensive fare leg rules can appear in Q.potentialAsRouteFareRules.
3. If Q.potentialAsRouteFareRules == R.potentialAsRouteFareRules, then 1 must appear in R.potentialAsRouteFareRules
4. If Q.potentialAsRouteFareRules == R.potentialAsRouteFareRules and 1 was the most extensive fare rule in R.potentialAsRouteFareRules, it must also be the most extensive fare leg rule for R because the potential fare leg rules are the same.
4. Q and R are thus equally extensive.
Q.E.D.

In Toronto, the fare system is not a simple linear map (like it is on, say, Caltrain or the MBTA). However, I assume that the most extensive fare leg rule is also (one of) the most expensive fare leg rules for a particular set of from and to stops, and assign order the fare rules based on descending fare, with ties receiving the same order. This last point is critical. If `fare_leg_rule` `order`s are set based on cost, as they are in Toronto so that the most expensive trip is always the one returned, `fare_leg_rule`s within the `as_route` network with the same fare must also have the same order. If A-C and B-C are the same price, you might be sloppy and assign order randomly for these two fare pairs. But to get proper transfer allowance domination logic, A-C must have a lower order or the same order as B-C. Otherwise, an A-C trip could kick out a B-C trip in domination because B-C would appear in its set of potential fare rules while A-C did not, which could lead to an incorrect result if the final journey is A-D, which might be more expensive than B-D. If A-C and B-C both have the same order, they will both appear in the potential fare rules, and an A-C trip will not be able to kick out a B-C trip that would not have A-C in its potential fare rules.
