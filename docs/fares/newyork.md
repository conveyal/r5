# New York in-routing fare calculation

The `NYCInRoutingFareCalculator` provides fare calculation for transit agencies in the New York metropolitan area. Due to the contract it was developed for, it only provides fare calculation for areas in New York State, and east of the Hudson River. Specifically, it calculates fares for the following agencies:

- MTA New York City Bus (all boroughs, and Bus Company)
- MTA New York City Subway
- MTA Staten Island Railway
- MTA Long Island Rail Road
- MTA Metro-North Railroad
- Nassau Inter-County Express (NICE)
- Suffolk County Transit
- Westchester Bee-Line
- NYC Ferry
- AirTrain JFK

## Algorithm details

The algorithm assigns every pattern in the feeds to a pattern type. During fare calculation, the pattern type sequences are used to assign fares and discounted transfers. Algorithm details for specific services are below. Some resources, such as scraped fares and stop to zone mappings, are stored as resources within `analysis-internal`, meaning that updating the GTFS from the December 2019 version used herein may cause some things to break. There is some error checking, but IDs in files in the `src/main/resources/fares/nyc` folder as well as hard-coded in `NYCStaticFareData.java` should be carefully checked if GTFS is ever updated. Additionally, all GTFS was merged together for this project, using the `Merge All Feeds` notebook (available separately), for historical reasons. Some feeds were modified before being added to the merged feed (see documentation below).

### MetroCard services

MetroCard services are all handled in a similar way. When a MetroCard-based service is first boarded, the service that was boarded is recorded. At a subsequent boarding of a MetroCard-accepting service, this information is cleared. If the new service allows a free or discounted transfer from the previous service, that discounted transfer is honored; otherwise a new ticket is purchased, and the new service replaces the previous service as the MetroCard transfer source (we assume that the Metrocard can only hold one transfer at a time, although there are edge cases where this may not provide the cheapest trip—see "edge cases" section below). There are special cases to allow a second discounted transfer on NICE and Suffolk County routes; see specific sections below.

MetroCard transfer allowances are assumed to expire two hours after they are first purchased. We assume that you only need to _board_ the final vehicle within two hours, not necessarily _alight_ from it, since the system does not actually know when you leave the system.

After calculating the fare for a journey prefix, if there is still a MetroCard-based service recorded as a transfer allowance source (i.e. it has not been cleared by a subsequent boarding), this is added as a field in the transfer allowance. Transfer allowances with different MetroCard sources are considered incomparable (while some may be strictly better than others, defining such a "pecking order" complicates the algorithm, and treating them as incomparable does not affect tractability). In addition to the MetroCard transfer source, the transfer expiry is recorded (although this is generally past the max clock time of the analysis, and is thus re-set to improve algorithm performance, as described in the last paragraph of Section 3.4 of [Conway and Stewart, 2019](https://files.indicatrix.org/Conway-Stewart-2019-Charlie-Fare-Constraints.pdf)). Additionally, whether the last ride ended within the subway fare gates is recorded, and this information is updated at transfers, since being inside subway fare gates provides cheaper onward journeys when you can make a free transfer to another subway train.

The maximum transfer allowance from MetroCard services is $3 for most services (due to the $3 discount on express buses), $6.75 for express buses (free transfer to another express bus), $2.25 from Suffolk County Transit routes ($0.50 step-up needed for NICE), and $2.50 from Suffolk County routes after a transfer slip has been purchased ($2.50 discount off NICE buses).

#### MTA Local Bus and Westchester Bee-Line

The same fare rules and MetroCard transfer types are used for all MTA local buses, as well as the Westchester Bee-Line local bus routes (and the Roosevelt Island Tram, which has to be added to the TransportNetwork using a modification). Free transfers are accepted from other local buses, express buses, the subway, NICE buses assuming the transfer has not already been used once, and the Staten Island Railway. Once a free transfer to a local bus has occurred, the transfer allowance is cleared.

##### Edge cases

There are a number of special cases with the MTA Local Bus network that are not handled by the fare calculator.

###### Disallowed bus-to-bus transfers

The MTA disallows certain [bus-to-bus transfers](http://web.mta.info/metrocard/termsreg.htm): between any bus route and itself, and between a handful of specific pairs of routes. These are not implemented—the former due to tractability (the transfer every bus route would be incomparable to all others, because we don't know if the router may re-board that route in the future), and the latter due to convenience (too many rules to specify, and would have to create separate transfer allowances for these specific route pairs since they have different transfer privileges). These rules are mostly to prevent people from making a stop and then continuing their trip, which is not what we're modeling, so this limitation should have minimal effect on the results. But it is possible that some trips where it may be optimal to make one of these transfers will be presented with a fare that is too low.

###### Two-transfer bus routes

While normally a MetroCard only gets you one transfer, in [a few places (see table note)](https://en.wikipedia.org/wiki/New_York_City_transit_fares#Base_fares) you get two transfers or even three transfers. These transfers are not handled due to the difficulty of specifying the special rules, except for you are able to take a bus to the Staten Island Railway, then the Staten Island Railway, then the ferry, then a local bus or subway in Manhattan, and vice-versa.

###### Bus routes that end in the subway network

At Canarsie—Rockaway Parkway station in Brooklyn, it is possible to transfer between bus and subway within fare gates with no fare interaction, because the buses terminate inside the fare gates. This will only matter if there are multiple bus rides. The fare will be calculated correctly for a bus->subway or subway->bus ride because a free transfer will be used, but if there is a bus->bus->subway ride, this will not be charged correctly, because the second transfer is actually free in this case, even though the MetroCard transfer has been used, since there is no fare interaction. Similarly, bus->transfer at Canarsie->subway->bus only requires one fare payment in real life (because no fare interaction occurs at Canarsie), but in this implementation will require two fare payments.

#### MTA Express Bus

MTA Express Buses accept "upgrade" transfers from local bus/Bee-Line, subway, the Staten Island Railway, and NICE (documented in the [transfers section](http://www.nicebus.com/NICE/media/NiceBusPDFSchedules/NICE-n4_MapSchedule.pdf) here). The upgrade fare is $3.75 on top of the base fare of $2.75, meaning that it is cheaper ($6.50) to take a local bus and then an express bus than it is to just take an express bus—something that [the router will capitalize on](https://projects.indicatrix.org/fareto-examples/?load=express-bus-local-bus). MetroCards accept [free transfers from other express buses](https://projects.indicatrix.org/fareto-examples/?load=express-bus-transfer), as [documented in the Fare and Tariff manual, page 33](http://web.mta.info/nyct/fare/pdf/NYCTransit_Tariff.pdf).


Notably, the router does not allow transfers between two express buses, as these don't seem to be documented anywhere. Additionally, we assume you can only have one MetroCard transfer privilege at a time. For instance, if you ride express->express->local->local, we assume that the second express boarding requires payment of a new fare, which gives you a free transfer to the first local, but that the second local requires a new fare—you can't "save" the transfer from the first express bus. You certainly could if you had two MetroCards, but that's out-of-scope.

#### MTA Subway

The MTA Subway accepts free transfers from local buses, express buses, NICE buses assuming the transfer has not already been used once, and the Staten Island Railway. Within-gates transfers are also coded for most stations where they exist (they are not coded for stations only serving a single line as it is assumed changing directions at such a station would never be optimal). Within-gates transfers allow riders to transfer to another line without affecting their transfer allowances, because there is no interaction with the fare system. Within-gates transfers are created by the `NYCT Subway fares` notebook and stored in the `src/main/resources/fares/nyc/mta/subway_transfers.csv` file which lists station names and what "station area"—i.e. within-gates connected platforms—they are a part of. The router will correctly differentiate between routes [with a free behind-gates transfer](https://projects.indicatrix.org/fareto-examples/?load=subway-in-system) and those that [require exiting the gates and paying a new fare](https://projects.indicatrix.org/fareto-examples/?load=out-of-subway).

There are a few places where MetroCard free transfers are provided to create "virtual" behind-gates transfers ([example](https://projects.indicatrix.org/fareto-examples/?load=junius)).

#### Staten Island Railway

Fares on the Staten Island Railway are paid only at St George and Tompkinsville, either on entering or exiting, because it is (or was) assumed that most SIR riders were going to the St George Ferry Terminal. Tompkinsville is very close to St George so fares are collected there as well to prevent people circumventing the fares. Intermediate rides on the SIR [are free](https://projects.indicatrix.org/fareto-examples/?load=sir-free), and do not produce a transfer allowance, nor affect an existing one, since there is no fare interaction. The Staten Island Railway accepts free transfers from local bus/Bee-Line, express bus, subway, and NICE (except after one NICE transfer has already occurred).

The router will [trade off walking to a free station with paying the fare at the terminus](https://projects.indicatrix.org/fareto-examples/?load=sir-pay-free-tradeoff).

##### Two transfers between Staten Island Bus, SIR, and Lower Manhattan bus/subway

If you ride a local bus to the Staten Island Railway, then take the ferry to Manhattan, [you can board any local bus or subway in lower Manhattan for free](http://web.mta.info/nyct/sir/sirfare.htm)—i.e. you get an extra transfer after your first transfer to the SIR. While there are [other places where a second free transfer is provided](https://en.wikipedia.org/wiki/New_York_City_transit_fares), this one is the only one that is implemented in the fare calculator.

This is operationalized by defining a series of additional transfer sources in the algorithm, similar to how Suffolk and NICE's second transfer system is implemented, but with some additional complexity. First of all, since this extra transfer only works on trips that travel from Staten Island to Manhattan or vice-versa, they are only allowed after riding the Staten Island Ferry (which is free and does not trigger any interaction with fare collection systems). Second, the restrictions on which local buses you can transfer to are not implemented, but they are enforced by only allowing transfers on foot from the Lower Manhattan ferry terminal. For a trip originating on Staten Island and traveling via local bus, Staten Island Railway, Staten Island Ferry, and bus or subway in lower Manhattan, the following sequence of MetroCard transfer sources will be generated:

1. `METROCARD_LOCAL_BUS` after riding the local bus on Staten Island
2. `LOCAL_BUS_TO_SIR` after riding the Staten Island Railway _and exiting through one of the terminals where the fare is paid_ (this could also be restricted to the St George terminal if desired, but that is not currently implemented). This special transfer allowance does not provide a discount to any service, and is cleared if you ride anything other than the SI Ferry next, but when you ride the Staten Island Ferry, it is exchanged for:
3. `LOCAL_BUS_TO_SIR_TO_SI_FERRY` after riding the ferry.
4. The `LOCAL_BUS_TO_SIR_SI_FERRY` transfer allowance is accepted by any local bus or subway service, but the transfer allowance is cleared implicitly by any other MetroCard service (e.g. express bus), or explicitly if a MetroCard service is not used next (e.g. NYC Ferry)

Here are examples of using a local bus to the Staten Island Railway to the ferry to [a local bus](https://projects.indicatrix.org/fareto-examples/?load=bus-sir-ferry-bus) and [the subway](https://projects.indicatrix.org/fareto-examples/?load=bus-sir-ferry-subway).

The reverse case is implemented differently, because the order of the Staten Island Ferry and Staten Island Railway are different. Additionally, in the reverse case, it is not enforced that you get off the subway or local bus near the ferry terminal—there is no way for the MTA to know that anyhow. You can take local bus -> NYC Ferry -> SI Ferry -> SIR -> Local Bus for a single fare, although finding an example that is time-optimal may be impossible.

Here is how the reverse case would generate transfer sources:

1. `METROCARD_LOCAL_BUS` or `METROCARD_SUBWAY` after riding the local bus or the subway, respectively.
2. `LOCAL_BUS_TO_SI_FERRY` or `SUBWAY_TO_SI_FERRY` after riding the Staten Island Ferry. These are accepted on any service that accepts a local bus or subway transfer, because the MetroCard doesn't actually know you took the ferry and can't update your transfer allowance based on it.
3. However, if you transfer to the Staten Island Railway _and traverse one of the fare stops_, rather than being consumed, these transfer allowances are converted to a `LOCAL_BUS_OR_SUBWAY_TO_SI_FERRY_TO_SIR`
4. `LOCAL_BUS_OR_SUBWAY_TO_SI_FERRY_TO_SIR` is accepted only by local buses. These have to be local buses on Staten Island, because that's the only local bus you could ride after riding the Staten Island Railway. This transfer allowance is cleared if the transfer from the SIR is not to a local bus, to prevent the router from assembling routes that look like subway -> SI Ferry -> SIR -> SI Ferry -> local bus.

Here are examples of using a [local bus](https://projects.indicatrix.org/fareto-examples/?load=bus-ferry-sir-bus) and [the subway](https://projects.indicatrix.org/fareto-examples/?load=subway-ferry-sir-bus) to access the ferry, and then changing to the SIR, and then to a local bus.

###### Other related cases

To prevent the router from getting a free transfer by doubling back on the SI Ferry, if one of the `*_TO_SI_FERRY` MetroCard transfer allowances is held _on_ the Staten Island ferry, it is re-set to whatever its original type was. To see why this is a problem, consider this route: SI Local Bus -> SI Ferry -> SI Ferry -> SIR -> SI Local Bus. If it were not for this rule, this route would only cost 2.75. But instead, the router does not find this route, but does find a [bus->SIR->bus route entirely on Staten Island, costing $5.50](https://projects.indicatrix.org/fareto-examples/?load=bus-sir-bus-no-ferry)

Riding the subway or bus to the ferry to something _other_ than the Staten Island Railway should behave exactly as if the ferry had never been ridden at all, and it does: for [bus -> ferry -> bus](https://projects.indicatrix.org/fareto-examples/?load=bus-ferry-bus) and for [subway -> ferry -> bus](https://projects.indicatrix.org/fareto-examples/?load=subway-ferry-bus) and for [bus -> ferry -> subway](https://projects.indicatrix.org/fareto-examples/?load=bus-ferry-subway).

##### Other edge cases

We assume that a trip from Tompkinsville to St George requires two fare payments, although this isn't documented. A transfer from a bus can only be used for one of them. They are so close together this shouldn't affect results much.

#### NICE

NICE buses behave very similar to local buses, with [a few caveats](https://www.vsvny.org/vertical/sites/%7BBC0696FB-5DB8-4F85-B451-5A8D9DC581E3%7D/uploads/NICE-n1_Maps_and_Schedules.pdf). They accept a single transfer from any other MetroCard service. They accept _two_ free transfers from NICE, which is handled by creating a special NICE_ONE_TRANSFER MetroCard transfer allowance source that is set as the source after a single NICE->NICE transfer, and is only accepted by another NICE bus. Transfers from Suffolk Transit to NICE cost $0.25, plus you have to buy a transfer slip from Suffolk Transit for $0.25. If the transfer slip is already purchased due to a previous Suffolk-Suffolk transfer, [a $0.25 surcharge is applied and the transfer allowance is cleared](https://projects.indicatrix.org/fareto-examples/?load=suffolk-suffolk-nice). If the transfer slip is not purchased, [a total of $0.50 is charged (for the slip and the upgrade fare), and the transfer allowance fare](https://projects.indicatrix.org/fareto-examples/?load=suffolk-nice). This means you can do Suffolk-Suffolk-NICE but not Suffolk-NICE-NICE for a single fare. [This Wikipedia article](https://en.wikipedia.org/wiki/Suffolk_County_Transit#cite_note-Suffolk_County_Transit_Fares-17) suggests that a _separate_ Suffolk-NICE transfer slip exists, but I can't find that documented anywhere official, so I've gone with the approach described above. If a Suffolk-NICE transfer slip did exist, the fare for Suffolk-Suffolk-NICE is probably $2.25 Suffolk base fare + $0.25 Suffolk transfer slip + $0.50 NICE transfer slip, so the router would understate the fare for this trip by $0.25. [This route](https://projects.indicatrix.org/fareto-examples/?load=suffolk-nassau) demonstrates the Suffolk to NICE transfer.

The router correctly handles that you can ride [three NICE buses for one fare](https://projects.indicatrix.org/fareto-examples/?load=nice-three-rides), but can only get a free ride on the MTA [after one transfer](https://projects.indicatrix.org/fareto-examples/?load=nice-to-mta), not [after two](https://projects.indicatrix.org/fareto-examples/?load=nice-nice-to-mta).

NICE buses all specify [which lines you can connect to](https://www.vsvny.org/vertical/sites/%7BBC0696FB-5DB8-4F85-B451-5A8D9DC581E3%7D/uploads/NICE-n1_Maps_and_Schedules.pdf). This is ignored, but is approximated by clearing the transfer allowance if a non-MetroCard service is ridden—so you [can't for example ride NICE -> LIRR -> NICE and get a free transfer to the second NICE ride](https://projects.indicatrix.org/fareto-examples/?load=nice-three-rides).

#### Suffolk

Suffolk County Transit fares are implemented as if they used the MetroCard system, even though they don't, but this should be fine and simplifies implementation. The only reason this would be a problem would be if you had a MetroCard transfer that you wanted to hold on to but ride a Suffolk County route in between, e.g. take a local bus in Queens, then ride the LIRR out to Suffolk County, ride the bus, take the LIRR back, and get your free transfer to the subway. Might make for a fun outing, but not something that would be done on an optimal trip (and may not even be possible within the two-hour validity period).

Suffolk County Transit accepts and consumes a free transfer from NICE (not the second transfer), or from another Suffolk County route. Two transfers are allowed on a Suffolk County route—to get a transfer, you have to purchase a transfer slip for $0.25, which then gets you two transfers (to either NICE or Suffolk, see above). Similar to NICE, this is handled by setting a special SUFFOLK_ONE_TRANSFER MetroCard source. It's not clear whether transfers are accepted from MTA; [this Wikipedia article](https://en.wikipedia.org/wiki/Suffolk_County_Transit#cite_note-Suffolk_County_Transit_Fares-17) says they are, but it's not in the link cited. Since Suffolk doesn't use MetroCard, I don't see how they would be (although the Wikipedia article says you can use MetroCard on Suffolk Transit with advance notice?). NICE will [still issue a paper transfer](https://www.nicebus.com/Fares-Passes/Learn-more-about-fares-and-passes), presumably for transfer to Suffolk Transit.

[This route](https://projects.indicatrix.org/fareto-examples/?load=suffolk) demonstrates a Suffolk County Transit trip with two transfers—one for $0.25, and a second for free. This route [demonstrates a free transfer from NICE to Suffolk](https://projects.indicatrix.org/fareto-examples/?load=nice-suffolk)

### BxM4C

The BxM4C (Westchester-Manhattan Express) route has a special fare with no free/discounted transfers. It does not affect any existing MetroCard transfer allowances either, though in practice it might since you can pay with a MetroCard. However, this is undocumented.

### Airtrain JFK

Airtran JFK has a flat fare, paid on boarding or alighting at the two stops that connect to the subway (Jamaica and Howard Beach). There are no discounted transfers.

### Staten Island Ferry

The Staten Island Ferry is free. It does not affect transfer allowances.

### NYC Ferry

The NYC Ferry service costs $2.75 and does not affect transfer allowances, because there are no discounted transfers.

### NYC Ferry shuttles

NYC Ferry also runs free shuttle buses. These do not affect transfer allowances. I can't find anywhere where it says that these can only be used in conjunction with a ferry, and enforcing that would be tricky anyhow. Pickup_type and dropoff_type is 0 at all stops, but we could change that to only allow pickups/dropoffs at the ferry terminal.

### Metro-North Railroad

No transfers are allowed between lines, except in Connecticut, it appears---Metro-North won't sell you a ticket from Port Chester to Wakefield, for example. Since we're dropping CT, just have standard fares with no transfers. Note that the Metro-North also operates ferries and buses, but these are not included in the GTFS. All service in Connecticut is dropped by the `Drop Services from Metro-North Feed` notebook. This notebook also splits the routes into peak and off-peak versions, forcing them into separate patterns, which allows the routing algorithm to correctly trade off waiting for an off-peak train to save money, as documented in Section 5 of [Conway and Stewart, 2019](https://files.indicatrix.org/Conway-Stewart-2019-Charlie-Fare-Constraints.pdf).

#### Background

The MNR uses a zone-based fare system, but the zones are not well publicized, so I treat it as a stop-to-stop fare system, and scrape fares from [the Metro-North fare calculator](http://as0.mta.info/mnr/schedules/sched_form.cfm). Metro-North doesn't say much about transfers, but since not every train stops at every stop, so you might need to transfer to reach a specific stop. However, it seems that you [can't transfer from the Harlem/Hudson to New Haven lines](http://www.iridetheharlemline.com/2010/09/22/question-of-the-day-can-i-use-my-ticket-on-other-lines/), even though it could make sense to do so at certain times. For instance, a trip from Mamaroneck (NH line) to Trenton (Harlem line) doesn't require a change of direction, but nevertheless the MTA won't sell you a ticket for this trip.

`mnr_fares.csv` in R5 (`src/main/resources/fares/nyc/mnr/`) contains fares for all station pairs on each line. It is created by the `Metro-North Fare Scraping` notebook, which uses a graph search algorithm to find all "downstream" stations from each stop on each line in each direction (it actually treats the Harlem/Hudson as a single line, but this shouldn't matter since there shouldn't actually be optimal changes between them, and even if there were, this would just mean some extra fares were retained).

#### Algorithm

As with most services in the NYC graph, there are two linked but distinct parts of the algorithm: the fare computation engine and the transfer allowance comparison algorithm.

##### Fare computation

When the Metro-North is first boarded, the board stop, line, and peak/off-peak is recorded. If the next ride is on Metro-North, one of two things happens:

1. If the direction and line of the ride match the previous ride, the previous ride is extended to the destination of this ride, and the fare is calculated accordingly. This allows people to change from express to local, or electric to diesel, service if required. If this ride is peak, and the previous wasn't, the ticket type is set to peak. This does mean that the algorithm will prevent someone from combining peak and off-peak tickets to save money. **However, the algorithm _will_ do things like ride a throwaway bus or walk to the next stop to force such a combination, if it is cheaper,** because of the difficulty of handling negative transfer allowances (see notes in fare scenarios section for more on this). Fixing this undesired behavior is tricky.
2. If the direction and line do _not_ match the previous ride, the fare is paid for the previous ride, and a new ride is started.

If at any point the path leaves the MNR system, either by taking another transit vehicle or walking to another stop, the MNR ride is ended and the fare is paid. This is part of what causes the undesired behavior described above.

##### Transfer allowance

A Metro-North transfer allowance contains four items:
- The board stop
- The direction (0/1 corresponding to outbound/inbound)
- Whether a peak train has been ridden (if a peak train is ridden anywhere on a single ticket, that ticket must be a peak ticket)
- The line (Harlem, Hudson, or New Haven—with all the New Haven branches folded into the New Haven line)

If any of these are not equal when comparing two MNR transfer allowances, both paths are retained. This will retain more paths than strictly necessary, but the Metro-North system is small enough it remains tractable and it's not worth spending more expensive development time to save miniscule amounts of cheap computing time.

- Since fares are determined by the board stop and the (ultimate) alight stop, which is not known, boarding at a different stop could yield a different level of discount on the full trip
- There are no free transfers when changing direction, so an inbound transfer allowances covers a completely disjoint set of services from an outbound one
- Either a peak or an off-peak transfer allowance could yield the cheapest trip
  - A peak transfer allowance could offer a larger discount on a later ride on a peak train, because the user would not have to pay the delta between offpeak and peak for the segment they've already ridden
  - An offpeak transfer allowance could yield a larger discount on an off-peak train, because the rider won't have to pay the peak fare for that train (recall that peak/off-peak fares are at the ticket level, not the train level—a ticket that uses _any_ peak trains must be a peak ticket)
- There are [no free transfers](http://www.iridetheharlemline.com/2010/09/22/question-of-the-day-can-i-use-my-ticket-on-other-lines/) for one-way ticket holders between the New Haven and Harlem/Hudson lines. At least based on that post, you can transfer between the Harlem and Hudson lines (or you could in 2009), but those lines only share two stops—Harlem-125th and Grand Central—so it's not clear why you would.

Note that having a Metro-North transfer allowance is not always advantageous—it may be cheaper to get on at a later stop, rather than continuing an existing ride.

### Long Island Rail Road

<!-- TODO talk about notebooks and splitting of peak/offpeak -->

Calculates fares for and represents transfer allowances on the MTA Long Island Rail Road. One nice thing is that the LIRR is a small
network, so we can be kinda lazy with how many journey prefixes get eliminated - we only consider journey prefixes comparable
under the more strict domination rules (Theorem 3.2 in the paper at https://files.indicatrix.org/Conway-Stewart-2019-Charlie-Fare-Constraints.pdf)
if they boarded at the same stop, alighted at the same stop, used the same stop to transfer from inbound to outbound
(if there was an opposite-direction transfer), and used the same combination of peak/offpeak services. Out of laziness, we set the maximum transfer allowance for any LIRR ticket to the maximum fare on the LIRR - we know it can't be more than that, and it's okay if it's a loose upper bound (see the Metro-North section for more info on why this is okay.)

In order to properly handle peak and off-peak fares, the notebooks preparing LIRR data split LIRR routes into peak and off-peak versions, as with Metro-North. Unfortunately, the LIRR does not flag trips as peak or off-peak in their GTFS, so we guess which trips are peak/off peak based on arrival times at the New York terminals, and assume that trains that don't go to New York City are off-peak. This is not perfect, but since peak and off-peak fares are the same outside New York City, it's pretty close.

LIRR fares are really complicated, because of complex and undocumented transfer rules. The fare chart is available at
http://web.mta.info/lirr/about/TicketInfo/Fares.htm, but it does not include anything about fares where you have to ride
inbound and then outbound (for instance, based on the fare chart, Montauk to Greenport on the eastern end of Long
Island are both in Zone 14, and thus should be a $3.25 fare, but to get between them, you have to ride at least to
Bethpage (Zone 7). So there must be a more expensive fare, because otherwise anyone who was going from Montauk to Babylon,
say, would just say they were planning to transfer and go back outbound.

LIRR deals with this situation by specifying via fares. They have no documentation on these that I can find, so I
scraped them from their fare calculator at lirr42.mta.info. For the Montauk to Greenport trip described above, for
example, the fare calculator returns that a trip via Jamaica is 31.75 (offpeak), and a trip via Hicksville is 28.00
(offpeak). This is still ambiguous - what if you make a trip from Montauk to Greenport but change at Bethpage instead
of Hicksville? We are assuming that in this case, you would be able to use the via fare for Hicksville.

If there is a
via fare for a station specified, we assume that it can also be used for any station closer to the origin/destination
than the via station. We define closer as being that that station is reachable from the via station via only outbound
trains in the common Inbound-Outbound transfer case, and reachable via only inbound trains in the rare Outbound-Inbound
case. If there is no via fare specified, we use two one-way tickets. (The second ticket may be a via fare if there is
another opposite direction transfer, for instance Hicksville to Oceanside via Babylon and Lynbrook, but otherwise we
use via fares greedily. For an A-B-C-D trip, if there is a via fare from A to C via B, we will buy an ABC and a CD fare
even if AB and BCD would be cheaper.)

This is further complicated by the fact that there are other transfers that are not via transfers. For example, to
travel from Atlantic Terminal to Ronkonkoma on LIRR train 2000, you must change at Jamaica. We thus assume that you
can change between any two trains anywhere and as long as you continue to travel in the same direction, it is treated
as a single ride for fare calculation purposes.

As with Metro-North, we assume that if at any point you leave the LIRR system (walking from West Hempstead to Hempstead, or taking a NICE bus from Bethpage to Massapequa, say), you have to buy a new ticket when you re-board. This does mean that odd routes could result if it is cheaper to purchase two separate tickets for a journey, where the router leaves the LIRR and walks to the next stop so it is allowed to purchase a new ticket. Such behavior has not been observed in testing, but is theoretically possible.

The LIRR also accepts the [Atlantic Ticket](https://new.mta.info/fares-and-tolls/long-island-rail-road/atlantic-ticket), which is completely separate ticket that saves money on many trips in Queens and Brooklyn. I handled this by patching these fares into the zonal and via fare tables, and changing the zones of the eligible stops. This is done in the `LIRR Atlantic Ticket` ipython notebook.

The MTA's [Atlantic Ticket website](https://new.mta.info/fares-and-tolls/long-island-rail-road/atlantic-ticket) says:

> How to use Atlantic Ticket
> 1 .Board any Atlantic Terminal- or Penn Station-bound train (transfer at Jamaica required) at the participating Queens stations: Rosedale, Laurelton, Locust Manor, St. Albans, Queens Village, or Hollis.
> 2. Board any Atlantic Terminal-bound train at Jamaica.
> 3. Board any Atlantic Terminal-bound train at Nostrand Ave and East New York.
> 4. Use a One-Way LIRR ticket to Atlantic Terminal, then use your MetroCard for systemwide travel. Or, for greater savings, purchase the weekly Atlantic Ticket that includes a 7-Day Unlimited Ride MetroCard, so you can transfer to a subway or local bus at Atlantic Terminal and use your MetroCard all week long as often as you wish.

I think that these are intended to be options, not a list of steps (the numbers make it confusing). Additionally, the numbers imply that you can only use the Atlantic Ticket to travel inbound. That doesn't seem right, so I interpret the instructions above as "you can travel between any of these stations, as long as you don't leave the Atlantic Ticket area, for $5".

LIRR does say:

> Can I combine Atlantic Ticket and a regular LIRR ticket?
> No. Atlantic Ticket may not be combined with a regular LIRR ticket for travel to/from any other LIRR stations, including Kew Gardens, Forest Hills, Woodside, and Penn Station

And this makes sense for a single trip—I can see why they wouldn't want you to ride from Laurelton to Jamaica on an Atlantic Ticket, and then on to Penn Station on a regular LIRR ticket, in a one-seat ride. But how could they possibly keep you from riding from Laurelton to Jamaica on an Atlantic Ticket, _changing at Jamaica_, and then riding on to Penn Station with a regular LIRR ticket? Disallowing that is tricky, both from a fare-enforcement standpoint _and_ from a routing standpoint—so I'm going to assume that a shrewd traveler can get away with combining tickets in this way. The router will only allow this if you're changing directions, otherwise the two rides will be concatenated and treated as a single ride.

## Fare scenarios

For the project this fare calculator was originally built for, we had a need to create several fare scenarios relating to Metro-North and the Long Island Rail Road. These are operationalized by adding additional options to the fare calculator, which are described below. Thus, scenarios can be modeled by specifying these options in the `inRoutingFareCalculator` in the `ProfileRequest`. For instance, specifying free transfers from the LIRR to local buses and subways might look like this:

```json
{
  "fromTime": 24000,
  "toTime": 27200,
  ...
  "inRoutingFareCalculator": {
    "type": "nyc",
    "lirrMetroCardTransferSource": "METROCARD_LOCAL_BUS"
  }
}
```

### Free transfers from LIRR/MNR to other Services

Rather than set up a complete infrastructure for specifying the discounts a rider should receive when transferring from the LIRR or MNR to another service, we allow these commuter rail systems to take on the transfer allowances of any MetroCard service, like a chameleon. Setting the field `lirrMetrocardTransferSource` to `METROCARD_LOCAL_BUS`, for example, means that any transfers that are free or discounted from local buses will also be considered free or discounted from the LIRR, with all the rights, privileges, and honors thereunto appertaining. `metroNorthMetrocardTransferAllowance` is similar. There are many options for what this value can be set to, for a full list see `NYCPatternType.java`. Note that some of the pattern types (e.g. `LIRR_PEAK` or `LIRR_OFFPEAK`) are not Metrocard services and will not yield any discount on future services.

Consider this LIRR-Subway trip, which [costs $16.75 under current fare policy](https://projects.indicatrix.org/fareto-examples/?load=lirr-subway-base-fare) and only [$14 under a policy with free transfers](https://projects.indicatrix.org/fareto-examples/?load=lirr-subway-fare-scenario) due to saving $2.75 on the transfer to the subway. Note that the transfer allowance after leaving the LIRR is set to `METROCARD_LOCAL_BUS`.

The Metro-North transfer allowance works the same way. Consider this trip, which [costs $15.50 under current fare policy](https://projects.indicatrix.org/fareto-examples/?load=metro-north-subway-base-fare) but only [$12.75 under a policy with free transfers](https://projects.indicatrix.org/fareto-examples/?load=metro-north-subway-fare-scenario), again due to a $2.75 savings on the transfer. As before, the transfer allowance can be inspected and is set correctly to `METROCARD_LOCAL_BUS`.

Note that setting the `metrocardTransferSource` to `METROCARD_SUBWAY` is generally _not_ what you want, as it will not allow free transfers to the subway, since there are no free transfers between subway lines (explicitly coded free transfers at the same stop or connected stops excluded).

### Free transfers from other services to the LIRR/MNR

Free transfers to the commuter rail system can be specified by including a map `toLirrDiscounts` or `toMetroNorthDiscounts` in the specification of the InRoutingFaceCalculator. This maps from various MetroCard transfer sources to discounts off the Metro-North/LIRR. As before, the MetroCard transfer sources are defined in `NYCPatternType`, but not all are MetroCard transfer sources (e.g. `LIRR_PEAK` is not because the LIRR does not use the MetroCard system) For instance, the following map gives a $2.75 discount off the LIRR for transfers from MTA local bus, subway, or NICE bus:

```json
{
  "METROCARD_LOCAL_BUS": 275,
  "METROCARD_SUBWAY": 275,
  "METROCARD_NICE": 275
}
```

Note that the transfer allowance is consumed when boarding the LIRR in this case; it is not possible to carry one of these transfer allowances with you on the LIRR and use it later. For that reason, discounts less than $2.75 may lead to fare _increases_, because you save less on the LIRR than you lose by not using the free transfer later. Even with a discount of $2.75, this may still be the case---if there was a transfer from MTA Local Bus -> LIRR -> Express Bus, instead of getting to save the $3.00 discount on the Express Bus, it is expended for a paltry $2.75 discount on the LIRR. (Recall that a $2.75 local bus fare inexplicably yields a $3 discount off an express bus fare.)

Here is an example of trip where NICE is used to connect to the LIRR, that [costs $15.25 under current fares](https://projects.indicatrix.org/fareto-examples/?load=nice-lirr-base-fare) and [$12.50 under a fare scenario with a $2.75 discount on the LIRR transferring from NICE](https://projects.indicatrix.org/fareto-examples/?load=nice-lirr-fare-scenario). Note that the fastest trip in both these options does not change price—it is $16.75 in both the baseline and the fare scenario—because it involves two NICE buses before boarding the LIRR, so the transfer allowance is consumed before boarding. Note also that the fare scenario has one additional option—in the baseline it costs the same as the faster, two-transfer option, but due to the lowered fare for a single NICE to LIRR transfer it is now Pareto-optimal. No transfer allowances are changed when `toLirrDiscounts` is set—already-existing transfer allowances mean different things than they did before, but `METROCARD_LOCAL_BUS` allowances _still_ provide the same discounts as each other, even though they now provide discounts off the LIRR.

An example of this for Metro-North is this ride on the Westchester Bee-Line connecting to Metro-North, which [costs $15.50 in the base scenario](https://projects.indicatrix.org/fareto-examples/?load=bee-line-metro-north-base-fares) and [$12.75 with a discount specified](https://projects.indicatrix.org/fareto-examples/?load=bee-line-metro-north-fare-scenario).

Note that [_the code explicitly does not allow `toLirrDiscounts` and `lirrMetrocardTransferAllowances` to be used sequentially_](https://projects.indicatrix.org/fareto-examples/?load=lirr-no-second-discounted-transfer), and [similarly for Metro-North](https://projects.indicatrix.org/fareto-examples/?load=metro-north-no-second-discounted-transfer), in keeping with the general philosophy apparent in New York that you get a single free transfer. Note that in both examples no transfer allowance is set after leaving the commuter rail, even though a `metrocardTransferSource` was set—the second free transfer is explicitly prevented. Note than a route that requires two separate ticket purchases on Metro-North _will_ allow a second free transfer (because a new ticket has been purchased), while a route that requires two ticket purchases on the LIRR will _not_, due to implementation details. Ideally the LIRR would work the way Metro-North does but these instances are expected to be rare.

Similarly, when taking a commuter rail trip that requires purchasing multiple tickets, the discounted transfer will only be applied to the first ticket. For instance, a trip taking a Bee-Line bus to the Hudson line to the Harlem line [saves $2.75 on the first Metro-North ticket, but there is no discount on the second](https://projects.indicatrix.org/fareto-examples/?load=bee-line-metro-north-second-ride-full-price-fare-scenario), compared to the [same trip with base fares](https://projects.indicatrix.org/fareto-examples/?load=bee-line-metro-north-second-ride-full-price-base-fare). In theory this works with the LIRR as well, although I have been unable to find an example that requires two separate tickets.

Note that it is possible with this tool to create negative transfer allowances, which can cause the cheapest routes not to be found [as equations 4 and 5 of the algorithm assume transfer allowances are nonnegative](https://files.indicatrix.org/Conway-Stewart-2019-Charlie-Fare-Constraints.pdf). If a NICE bus gives a 1.75 discount off LIRR, and LIRR gives a 2.75 discount off local bus, riding the NICE bus before LIRR and foregoing the later discount makes the fare $1 higher than if you had discarded the NICE allowance beforehand.

### Overriding fares on Metro-North and the Long Island Rail Road

The other type of modification to the commuter rail system which is possible is to override the fares for specific journeys. This is done through two lists that can be passed to the `NYCInRoutingFareCalculator`: `metroNorthFareOverrides` and `lirrFareOverrides`. The elements of these lists look like this:

```json
{
  "fromStopId": "east_of_hudson:lirr237",
  "toStopId": "east_of_hudson:lirr241",
  "peakFare": 275,
  "offPeakFare": 275
}
```

Where `fromStopId` is a feed-ID-qualified stop ID for the from stop, `toStopId` is a feed-ID-qualified stop ID for the to stop, and `peakFare` and `offPeakFare` are the fares that should be charged for this stop pair. Note that normal rules about changing lines requiring paying a new fare on Metro-North, multiple consecutive rides in the same direction being treated as a single ride, and so on still apply.

It is again possible to create negative transfer allowances by doing this, by creating lower fares to intermediate stations such that a trip from A to C costs more than a trip from A to B plus a trip from B to C. However, there is special code to handle this situation in the router; specifically, having an LIRR or MNR transfer allowance is not considered better than not having one, and Theorem 3.1 from [the original paper](https://files.indicatrix.org/Conway-Stewart-2019-Charlie-Fare-Constraints.pdf) is disabled altogether. For instance, for this route in the city fare scenario, [the cheaper option takes the D train to Melrose and then boards a train to North White Plains](https://projects.indicatrix.org/fareto-examples?load=mnr-negative-transfer-allowance-correct), rather than taking Metro-North all the way from Grand Central, to save a few bucks. Without the special code to handle this situation, [this route would not be found](https://projects.indicatrix.org/fareto-examples?load=mnr-negative-transfer-allowance-incorrect)—although this example is somewhat contrived, since I had to set the `metroNorthMetrocardTransferSource` to `METROCARD_SUBWAY` to cause the problem to occur, and the alternate option has the same travel time and fare.

Note that the router will still do anything in its power to get rid of negative transfer allowances. For instance, it [may ride a bus one stop at Jamaica](https://projects.indicatrix.org/fareto-examples/?load=lirr-negative-transfer-correct). Without the special code that accounts for negative transfer allowances, it will not find that route, [though it will find a similar one that rides the subway twice at Jamaica to clear the transfer allowance and get a new subway allowance](https://projects.indicatrix.org/fareto-examples/?load=lirr-negative-transfer-incorrect) that allows the route to not be dominated by the direct LIRR train when transferring back to Jamaica.

For instance, to change the fare from North White Plains to Harlem-125th from [$9.75](https://projects.indicatrix.org/fareto-examples/?load=north-white-plains-harlem-125th-base-fare) to [$4](https://projects.indicatrix.org/fareto-examples/?load=north-white-plains-harlem-125th-fare-scenario), you would add this JSON to the `metroNorthFareOverrides`:

```json
{
  "fromStopId": "east_of_hudson:mnr_76",
  "toStopId": "east_of_hudson:mnr_4",
  "peakFare": 400,
  "offPeakFare": 400
}
```

Similarly, for the LIRR, to change the fare from Hicksville to Penn Station from [$10.25](https://projects.indicatrix.org/fareto-examples/?load=hicksville-penn-station-base-fare) to [$7.10](https://projects.indicatrix.org/fareto-examples/?load=hicksville-penn-station-fare-scenario), you would add this JSON to the `lirrFareOverrides`:

```json
{
  "fromStopId": "east_of_hudson:lirr92",
  "toStopId": "east_of_hudson:lirr237",
  "peakFare": 710,
  "offPeakFare": 710
}
```

LIRR fare overrides have one additional option, `viaStopId`, to allow overriding via fares as well as direct fares. (Via fares involve an opposite-direction transfer, and are generally discounted from the cost of buying two tickets. However, if the cost of buying two tickets is cheaper than the via fare, the two-ticket fare will be used instead.) For instance, to change the fare from Westbury to St Albans via Jamaica from [$10.50](https://projects.indicatrix.org/fareto-examples/?load=westbury-jamaica-st-albans-base-fare) to [$6.00](https://projects.indicatrix.org/fareto-examples/?load=westbury-jamaica-st-albans-fare-scenario), you would add this to `lirrFareOverrides`:

```json
{
  "fromStopId": "east_of_hudson:lirr213",
  "toStopId": "east_of_hudson:lirr184",
  "viaStopId": "east_of_hudson:lirr102",
  "peakFare": 600,
  "offPeakFare": 600
}
```

Note that via fares are valid for transfers at any station closer to the origin and destination than the station the via fare is specified for. For example, [a via fare for New Hyde Park to Freeport via Penn Station will also apply to New Hyde Park to Freeport via Jamaica](https://projects.indicatrix.org/fareto-examples/?load=lirr-fare-scenario-downstream-xfer).
