# In-routing fare support

Conveyal Analysis supports computing fares within the routing algorithm, which allows the routing algorithm to find not only the fastest path, but all Pareto-optimal tradeoffs between travel time and fare (the cheapest route is always to walk, unless free transit is available, so generally just finding the cheapest route is undesirable). For instance, here are the Pareto-optimal options for travel from Salem, Massachusetts to Copley Square in Boston:

![Example Pareto surface for travel from Salem to Copley. The fastest option is to take the Newburyport/Rockport line followed by the Green Line. Slightly cheaper and quite a bit longer is to take the Newburyport/Rockport line followed by two buses. Much cheaper is an express bus followed by the green line, and cheapest is a bus to the blue line to the orange line. Each subsequent option takes longer than the last.](fareto.png)

Finding cheapest paths is implemented in the McRAPTOR (multi-criteria RAPTOR) router, since this is a multi-objective optimization problem. Correctly finding cheapest paths is a challenging problem, due to discounted transfers. It may make sense to take a more expensive and/or slower option at the start of your trip, if it provides a discounted transfer to a service you will take later. However, since the router does not know what transit routes will be ridden in later rides, this presents a challenge. We introduce a "transfer allowance" that tracks these possible discounts; full details are available in [this IJGIS paper](https://files.indicatrix.org/Conway-Stewart-2019-Charlie-Fare-Constraints.pdf).

Unfortunately, while there is a common data format for transit timetables (GTFS), no such format exists for fares. GTFS does include two different fare specifications (GTFS-fares and GTFS-fares v2), but they are not able to represent complex fare systems. As such, unless and until such a specification becomes available, Conveyal Analysis includes location-specific fare calculators for a number of locations around the world. They have their own documentation:

- [New York](newyork.md)
- Boston (documentation coming soon)
- [GTFS-Fares v2](gtfs-fares-v2.md)
