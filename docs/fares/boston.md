# Boston in-routing fare calculation

The Boston fare calculator is the original fare calculator used as a an example in [our original paper on computing accessibility with fares](https://files.indicatrix.org/Conway-Stewart-2019-Charlie-Fare-Constraints.pdf). It handles fares for the [Massachusetts Bay Transportation Authority](https://mbta.com), which provides most of the transit service in the Boston area, including subways, light rail, commuter rail, local and express buses, bus rapid transit, and ferries. As of summer 2018, when we wrote the paper, the fares were as shown in Table 2 of [the original paper](https://files.indicatrix.org/Conway-Stewart-2019-Charlie-Fare-Constraints.pdf). This document details the implementation of this fare system in R<sup>5</sup>. The fare system is based on fares and modified GTFS from July 2018.

## General principles

The MBTA fare system for all modes _except_ ferry and commuter rail generally allows a single ``pay-the-difference'' transfer from one mode to another. For instance, after a $1.70 bus ride, you can ride the $2.25 subway by paying an upgrade fare of $2.25 - 1.70 = $0.55. If the first mode you paid full fare for was more expensive than the mode you're transferring to, the transfer is free. You generally get only one of these transfers, although after transfering from local bus to subway you then get one more free transfer to a local bus. Note that this single pay-the-difference fare structure can create [negative transfer allowances](https://indicatrix.org/post/regular-2-for-you-3-when-is-a-discount-not-a-discount/), for instance when transferring local -> express -> subway.

The fares for commuter rail and ferry are simpler. There are no discounted transfers from these modes; the fare for the ride is simply added to the cumulative fare paid, and the 

The Silver Line is [free when boarded at Logan Airport, and allows a free transfer to the subway](http://www.massport.com/logan-airport/to-from-logan/transportation-options/taking-the-t/), as are [the MassPort shuttles](http://www.massport.com/logan-airport/to-from-logan/transportation-options/on-airport-shuttle/), which can create [interesting trips using free airport shuttles and the Silver Line to avoid paying the subway fare](https://projects.indicatrix.org/fareto-examples/?load=bos-eastie-to-revere-sl&index=3).

## Subway

The subway fare is $2.25, with [free within-gates transfers](https://projects.indicatrix.org/fareto-examples/?load=bos-red-orange&index=0). However, transfers that require leaving the subway system [require a new fare](https://projects.indicatrix.org/fareto-examples/?load=bos-green-b-to-d&index=3). Transfers are generally assumed to be within-subway if they board at the same parent station as the previous alighting. However, there are exceptions. Park Street and Downtown Crossing are explicitly [connected behind faregates](https://projects.indicatrix.org/fareto-examples/?load=bos-dxc-park&index=0) by the [Winter Street Concourse](https://en.wikipedia.org/wiki/Winter_Street_Concourse).

Many stations on the MBTA system, most notably [Copley](https://en.wikipedia.org/wiki/Copley_station), do not allow a traveler to transfer to a train traveling in the opposite direction without a new fare payment. Of these, only Copley is treated as not having behind-gate transfers at all, except to trains traveling in the same direction. Copley is treated as such because it is the logical transfer point from an inbound E line train to an outbound train on any other line, or vice-versa, but this transfer is actually not allowed. The router will [recommend a transfer at Arlington, but still present the more expensive transfer at Copley if it is faster](https://projects.indicatrix.org/fareto-examples/?load=bos-green-copley-xfer&index=0). Transfers between trains traveling in the same direction [are allowed at Copley](https://projects.indicatrix.org/fareto-examples/?load=bos-green-copley-same-dir-xfer&index=0). All other stations where multiple lines split apart or cross have behind-gates transfers (Silver Line Way is not considered a station, but two separate stops, in the GTFS we are using). Other stations (e.g. Central) do not have behind-gates transfers, but such a transfer would only be used to change direction on the same line---something we assume is never optimal, since there is no express service on the MBTA subway system.

Similarly, no opposite-direction _or_ same-direction transfers are allowed at surface Green Line stops; once you leave the Green Line, you cannot reboard. We again assume that these transfers will never be optimal, and don't explicitly disallow them. The only exception we are aware of is if there are short-turns on any of the surface Green Line branches, the router may suggest a transfer at one of the surface stops. However, in this case, the rider could have also just waited at their origin stop for the full route train, and gotten the same arrival time, and in fact the router should find this route anyhow as a no-transfer route will always be found even if there is another multiple-transfer route, due to the RAPTOR search process. Transfers between lines can never occur at surface stops as no lines share surface stops. Trains do occasionally express to relieve bunching, but this is not as far as I know in the schedule, and when it does happen a free transfer is _de facto_ allowed.

The [Mattapan High-Speed Line](https://en.wikipedia.org/wiki/Ashmont%E2%80%93Mattapan_High-Speed_Line) is a trolley line that is depicted as part of the red line on official MBTA maps; it connects to the Red Line at the Ashmont terminus. Transfers between the Red Line and the Mattapan High-Speed Line [are free (p. 16)](https://cdn.mbta.com/sites/default/files/2020-09/2020-09-01-mbta-combined-tariff.pdf), and are treated by R<sup>5</sup> as if they were any other behind-gates transfer in the subway system; that is, a free transfer to local bus [can still be used after riding the Red Line and the Mattapan High-Speed Line](https://projects.indicatrix.org/fareto-examples/?load=bos-red-mattapan-bus&index=1).

Transfers to local buses from the subway [are free](https://projects.indicatrix.org/fareto-examples/?load=bos-red-orange-bus&index=0), while [transfers from local buses to the subway require an upgrade fare](https://projects.indicatrix.org/fareto-examples/?load=bos-bus-red&index=0). There is an exception to the usual rule of "one pay-the-difference" transfer for local buses and subways; [a rider transferring from a local bus to the subway can then transfer back to another local bus for free](https://projects.indicatrix.org/fareto-examples/?load=bos-bus-subway-bus&index=1). This is true [even when multiple subway lines are ridden in between the bus trips](https://projects.indicatrix.org/fareto-examples/?load=bos-bus-subway-subway-bus&index=1), as long as the rider does not leave the paid area of the system. It is not documented whether this is true for the other bus types, so we assume it is not.

Transfers to express buses [require an upgrade fare that brings the total in line with the express bus fare](https://projects.indicatrix.org/fareto-examples/?load=bos-subway-inner-express&index=0). Transfers from [express buses to the subway are free](https://projects.indicatrix.org/fareto-examples/?load=bos-express-subway&index=1). Because the amount of discount you get depends on what vehicle you transfer to, this can result in negative transfer allowances in some cases. In these cases, the algorithm may not find the lowest-cost path, when it involves a complex route that requires discarding a ticket or riding an extra "throwaway" transit vehicle, [as described in this blog post](https://indicatrix.org/post/regular-2-for-you-3-when-is-a-discount-not-a-discount/).

## Bus

Local buses cost $1.70 [with one free transfer](https://projects.indicatrix.org/fareto-examples/?load=bos-two-bus&index=0). A [third local bus ride requires a new fare payment](https://projects.indicatrix.org/fareto-examples/?load=bos-extra-bus-fare&index=1), but the router will also find [cheaper two-bus options even if they are slower](https://projects.indicatrix.org/fareto-examples/?load=bos-extra-bus-fare&index=0).

Transfer from [local to express buses require an upgrade fare payment](https://projects.indicatrix.org/fareto-examples/?load=bos-local-express&index=0), while [transfers from express to local buses are free](https://projects.indicatrix.org/fareto-examples/?load=bos-express-local&index=1).

[Inner express buses](https://projects.indicatrix.org/fareto-examples/?load=bos-inner&index=0) and [outer express buses](https://projects.indicatrix.org/fareto-examples/?load=bos-outer&index=1) have different fare characteristics but the same transfer characteristics.

## Silver Line

The Silver Line [SL1, SL2, and SL3 charge subway fares](https://www.mbta.com/fares/subway-fares), while the [SL4 and SL5 charge local bus fares](https://www.mbta.com/fares/bus-fares). This section details the fare system for the SL1, SL2, and SL3. The SL4 and SL5 are simply treated as local buses (described below).

The [base fare for the Silver Line is $2.25, like the subway](https://projects.indicatrix.org/fareto-examples/?load=bos-sl-base&index=1). However, things are more complicated when transfers are involved. Transfers between the Silver Line and the subway system are [free at South Station](https://projects.indicatrix.org/fareto-examples/?load=bos-red-silver&index=1) because the Silver Line enters a tunnel and platforms are physically connected behind fare gates. Transfers from the Silver Line [to](https://projects.indicatrix.org/fareto-examples/?load=bos-sl-bus&index=0) and [from]() buses are free, just as they are with the subway

A transfer [from the SL3 to the Blue Line at Airport](https://projects.indicatrix.org/fareto-examples/?load=bos-sl3-blue&index=1) is treated as a behind-gates transfer, even though it is not technically behind gates (the SL3 drops you off in the bus loop on the Massport side of the Airport station, and you have to tag in to board the blue line). The same is true for a [Blue Line to SL3 transfer](https://projects.indicatrix.org/fareto-examples/?load=bos-blue-sl3&index=1). However, this transfer is not allowed when the original boarding was on the Silver Line for free at Logan Airport, as we assume the transfer system cannot recognize this transfer. This is handled by tracking in the transfer allowance whether the subway was entered for free.

These assumptions about how transfers between the Silver Line and the Blue Line work are as close as we can get to the [documented policy from the MBTA](https://www.mbta.com/fares/transfers) which states that ``transfers at subway stations are free, if you exit the station you will pay the full subway fare to enter another station.'' It is not clear how transfers from the out-of-faregates busway station at Airport are handled, but [the SL3 was intended to connect to the Blue Line](https://blog.mass.gov/transportation/uncategorized/mbta-new-silver-line-3-chelsea-service-between-chelsea-and-south-station/) so it seems unlikely that this transfer would cost. As implemented, the algorithm cannot exactly replicate the CharlieCard system, as the CharlieCard system does not know where a user exited the system, so it may be that subway-subway transfers are simply allowed at Airport regardless of source. However, the implementation aims to reflect the spirit of the fare system.

When the SL1 from the airport is taken in between two local buses, the transfer allowance is not affected, other than to note that the user is now in the subway system, because there is no fare system interaction. This [allows the user to use the transfer to board another local bus](https://projects.indicatrix.org/fareto-examples/?load=bos-bus-sl1-bus&index=1), [even if a subway was also taken after the SL1](https://projects.indicatrix.org/fareto-examples/?load=bos-bus-sl1-red-bus&index=0).

## Ferries

Ferries have a separate fare structure, and [there are no transfer discounts to or from ferries, although the router will trade off ferries with cheaper terrestrial routes](https://projects.indicatrix.org/fareto-examples/?load=bos-ferry-tradeoff&index=0). Transferring to a ferry [does not yield any discount either](https://projects.indicatrix.org/fareto-examples/?load=bos-subway-to-ferry&index=0). This does mean that [riding a local bus, then a ferry, then another local bus only requires _one_ local bus fare, as the transfer from the first bus can be used on the second](https://projects.indicatrix.org/fareto-examples/?load=bos-bus-ferry-bus&index=0). Ferries from [Boston to Logan] or [to the South Shore] cost $9.25, while trips between Logan and the South Shore cost $18.50, regardless of whether [they are undertaken on a single ferry](https://projects.indicatrix.org/fareto-examples/?load=bos-logan-hull&index=0) or [with a transfer in downtown Boston](https://projects.indicatrix.org/fareto-examples/?load=bos-logan-hull-xfer&index=0). The [Charlestown-Downtown Boston ferry costs $3.50](https://projects.indicatrix.org/fareto-examples/?load=bos-ctown-ferry&index=0).

## Massport shuttles

[Massport shuttles are free.](https://projects.indicatrix.org/fareto-examples/?load=bos-massport-shuttle&index=0)

## Commuter rail

Commuter rail has a zone-based fare system, with fares from each zone to downtown Boston, as well as "interzone" fares for trips that do not start or end in downtown Boston. At the time this paper was written, there were no transfer discounts from commuter rail to other modes, [although some have since been piloted on one line](https://www.bostonglobe.com/2020/05/07/metro/coming-soon-fairmount-line-free-transfers-subway/).

Two broad classes of one-way commuter rail fares exist: zone fares and interzone fares. Zone fares are fares for trips beginning or ending in Zone 1A, which contains the Downtown Boston terminals as well as several other central stations in Boston, Cambridge, Medford, Malden, and Chelsea; for instance a Zone 5 fare would cover travel from Zone 5 to Zone 1A. Interzone fares are for trips that pass through other zones but not Zone 1A. For instance, an Interzone 3 fare would cover a trip from Zone 6 to Zone 4 (because it passes through three fare zones). The fares are as follows:

### Zone fares

- [Zone 1A <> Zone 1A: $2.25](https://projects.indicatrix.org/fareto-examples/?load=bos-1a-1a&index=0)
- [Zone 1 -> Zone 1A: $6.25](https://projects.indicatrix.org/fareto-examples/?load=bos-1-1a&index=3)
- [Zone 1A -> Zone 1: $6.25](https://projects.indicatrix.org/fareto-examples/?load=bos-1a-1&index=0)
- [Zone 2 -> Zone 1A: $6.75](https://projects.indicatrix.org/fareto-examples/?load=bos-2-1a&index=2)
- [Zone 1A -> Zone 2: $6.75](https://projects.indicatrix.org/fareto-examples/?load=bos-1a-2&index=4)
- [Zone 3 -> Zone 1A: $7.50](https://projects.indicatrix.org/fareto-examples/?load=bos-3-1a&index=4)
- [Zone 1A -> Zone 3: $7.50](https://projects.indicatrix.org/fareto-examples/?load=bos-1a-3&index=1)
- [Zone 4 -> Zone 1A: $8.25](https://projects.indicatrix.org/fareto-examples/?load=bos-4-1a&index=0)
- [Zone 1A -> Zone 4: $8.25](https://projects.indicatrix.org/fareto-examples/?load=bos-1a-4&index=2)
- [Zone 5 -> Zone 1A: $9.25](https://projects.indicatrix.org/fareto-examples/?load=bos-5-1a&index=2)
- [Zone 1A -> Zone 5: $9.25](https://projects.indicatrix.org/fareto-examples/?load=bos-1a-5&index=0)
- [Zone 6 -> Zone 1A: $10.00](https://projects.indicatrix.org/fareto-examples/?load=bos-6-1a&index=1)
- [Zone 1A -> Zone 6: $10.00](https://projects.indicatrix.org/fareto-examples/?load=bos-1a-6&index=0)
- [Zone 7 -> 1A: $10.50](https://projects.indicatrix.org/fareto-examples/?load=bos-7-1a&index=0)
- [Zone 1A -> Zone 7: $10.50](https://projects.indicatrix.org/fareto-examples/?load=bos-1a-7&index=0)
- [Zone 8 -> Zone 1A: $11.50](https://projects.indicatrix.org/fareto-examples/?load=bos-8-1a&index=1)
- [Zone 1A -> Zone 8: $11.50](https://projects.indicatrix.org/fareto-examples/?load=bos-1a-8&index=0)
- [Zone 9 -> Zone 1A: $12.00](https://projects.indicatrix.org/fareto-examples/?load=bos-9-1a&index=0)
- [Zone 1A -> Zone 9: $12.00](https://projects.indicatrix.org/fareto-examples/?load=bos-1a-9&index=0)
- Zone 10 (Wickford Junction) is outside of the analysis area of this project

### Interzone fares

Charged by the number of zones passed through, in whole or in part.

- [Interzone 1: $2.75](https://projects.indicatrix.org/fareto-examples/?load=bos-iz-1&index=0)
- [Interzone 2: $3.25](https://projects.indicatrix.org/fareto-examples/?load=bos-iz-2&index=0)
- [Interzone 3: $3.50](https://projects.indicatrix.org/fareto-examples/?load=bos-iz-3&index=0)
- [Interzone 4: $4.00](https://projects.indicatrix.org/fareto-examples/?load=bos-iz-4&index=0)
- [Interzone 5: $4.50](https://projects.indicatrix.org/fareto-examples/?load=bos-iz-5&index=0)
- [Interzone 6: $5.00](https://projects.indicatrix.org/fareto-examples/?load=bos-iz-6&index=0)
- [Interzone 7: $5.50](https://projects.indicatrix.org/fareto-examples/?load=bos-iz-7&index=0)
- [Interzone 8: $6.00](https://projects.indicatrix.org/fareto-examples/?load=bos-iz-8&index=0)
- [Interzone 9: $6.50](https://projects.indicatrix.org/fareto-examples/?load=bos-iz-9&index=0)
- Interzone 10 is not possible without Wickford Junction, which is outside the analysis area.

### Boundary zones

In the baseline, there is one station, Quincy Center, that is in a special 1A/1 boundary zone, which was due to a subway station closure at the time. Fares from Zone 1A/1 to Zone 1A stations [are charged the Zone 1A fare of $2.25](https://projects.indicatrix.org/fareto-examples/?load=bos-1a1-1&index=3), while trips from Zone 1A/1 to other zones [are charged the appropriate interzone fare as if the station was in Zone 1](https://projects.indicatrix.org/fareto-examples/?load=bos-1a1-6&index=0). The opposite is also true, for trips [from Zone 1A](https://projects.indicatrix.org/fareto-examples/?load=bos-1a-1a1&index=) and [from outlying zones](https://projects.indicatrix.org/fareto-examples/?load=bos-6-1a1&index=0).

### Transfers

Per the fare tariff, there are no discounted transfers at all on commuter rail. However, in practice and since the commuter rail system is a proof-of-payment system, and since some trains express during rush hour, it is possible to transfer and continue a same-direction trip, for instance a trip from [Worcester to Auburndale at rush hour, with a transfer in Framingham to to express service](https://projects.indicatrix.org/fareto-examples/?load=bos-cr-same-dir-xfer&index=0). The router calculates the fare for this as $8—two $4 interzone 4 fares for the two legs of the trip. However, in practice you purchase a $5.50 interzone 7 ticket for this trip. This type of express service is rare in the MBTA commuter rail system, and the transfer discounts are thus left unimplemented.

There are no discounted transfers [to other modes](https://projects.indicatrix.org/fareto-examples/?load=bos-cr-xfer&index=0) with pay-as-you-go fares on commuter rail, although [the router will trade off a longer trip on commuter rail with disembarking early and changing to local transit when it is cheaper to do so](https://projects.indicatrix.org/fareto-examples/?load=bos-cr-xfer&index=4). Similarly, there are no discounted transfers [from other modes](https://projects.indicatrix.org/fareto-examples/?load=bos-xfer-cr&index=0). Like ferries, when the commuter rail is used in between two other modes, [the transfer allowance from the first mode is preserved and can be used for a discounted transfer on the second mode](https://projects.indicatrix.org/fareto-examples/?load=bos-bus-cr-orange&index=0).
