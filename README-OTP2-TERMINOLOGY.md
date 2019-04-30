# Terminology
The Raptor algorithm is described in [a paper by Microsoft from 2012](https://www.microsoft.com/en-us/research/publication/round-based-public-transit-routing/). We plan to use the Range Raptor with Multi-criteria pareto-optimal search.

This section describe the terminology used within the context of the Otp2 Raptor implementation. The section should list 
most of the terms used and define them. Note that a term my mean something else in another context. Please use the terms
defined here and not any synonyms just because you feel like it. Feel free to suggest changes if you meen there exist a 
more precise term. We try to stay close to the terminology used in GTFS standard.   

# Raptor
Raptor is a graph algorithm that works in _rounds_. The search start from a list of access stops and arrival times (initial _stop-arrivals_). Then for each _round_ all trips from these stops is explored, and so are transfers from all stops reached. A new list of _stop-arrivals_ is found. This new list of _stop-arrivals_ is used as input for the nest round. The algorithm will terminate by it self, or can be stoped when you have the desired results. The reason this is much faster than the current OTP A* is that there is no need to maintain a priority queue of edges to explore. The routes act as a natural way to explore and usually there is a significant cost to transfer between routes - this is way Raptor perform so well. 

## Range Raptor (RR)
_Range Raptor_ works in iterations over minutes. Let say you want to travel from A to B sometime between 12:00 and 13:00. Then _Range Raptor_ start at 13:00, and for each minute run the search: 12:59, 12:58 ... until 12:00. The way Raptor works enable us to do this in a super efficient way only adding a small percentage to overall performance (compared to a singel Raptor search). This pack the trip and ensure finding the best trip in that period with departure time _after_ 12:00 o'clock. Search on arrival time is done in a similar way. The packing is only valid for the search time window; There might be a trip leaving after 13:00 that is faster than the trips found. 

## Standard Range Raptor (StdRR) 
Raptor gives us the optimal trip based on _arrival time_. With some overhead we can add _number of transfers_. _Range Raptor_ also gives us the shortest travel duration (within its search window). Standard Range Raptor is a search witch find all pareto optimal paths based on the _Range Raptor_ standard criteria:
 - _Destination arrival time_ 
 - _Number of transfers_
 - _Total travel time_

We use the term _Standard_ to distingush this from Multi-Criteria Range Raptor

## Multi-criteria Range Raptor (McRR) 
The McRR extend the _Standard Range Raptor_ by adding other criteria. Any criteria can be added to the standard criteria  OTP is today only optimized on cost, witch is a mix of _time, waiting time, walking distance, transfers_ and so on. So, a McRR search will return a *pareto-optimal* set of paths with at least these criteria:
- arrival time (seconds)
- number of transfers (scalar)
- travel duration (seconds)
- cost (scalar) (a function of anything else than mention above)

We will also experiment with extracting other criteria from the _cost_ like _walkingDistance_,  _operator_. The goal is to make this configurable so each deployment may tune this to their needs. Due to performance reasons it might not be 100% dynamic.

Because _Raptor_ is much faster than the _multi-criteria_ Raptor we will provide an option (request parameter) to run both _RR_  and _McRR_. We might even use _RR_ as a heuristics optimization for McRR. In a bench mark test, RR takes on average 80ms while McRR with the same configuration takes 400ms. If we add _walking distance_ as an extra criteria the average time increase to 1000ms. (The numbers provided are for relative comparison - it is to early to compare with the existing OTP performance).

## Pareto optimal/efficiency
All paths that is considered *pareto optimal* for a set of criteria is returned by the StdRR and McRR, but in most cases were we talk about pareto optimal paths, we talk about the McRR version - adding _cost_ or some other criteria. The set of pareto optimal paths can be a large set (> 500 paths).

### Pareto-set explained
See [Wikipedia](https://en.wikipedia.org/wiki/Pareto_efficiency) A pareto-set of paths/itineraries is a set where all elements are better (less than) for at least one criteria than all other elements in the set. Given a set `{ [9,2], [5,6],  [3, 8] }` then `[7, 4]` would make it into the set. This is because  7 < 9 (comparing the 1sr criteria, element 1), and 4 < 6 and 8 (comparing the 2nd criteria, element 2 and 3). `[6,7]` would not make it into the set because `[5,6]` is better for both criteria. 

### Comparison, Dominance and Mutual dominance
When two criteria/values are compared we say that a value _dominates_ another value if it is better. A vector dominates another, if one of its criteria dominates the corresponding criteria of the other vector. Two vectors are mutual dominant if they dominates each other, both entities are pareto-optimal. Lets say _less_ is better, then [1,5] and [4,2] mutually dominates each other. This is because 1 < 4 (1st value) and 5 > 2 (2nd value). We can also have mutual dominance between to criteria, if we compare using `!=` or _Relaxed comparison_ the comparison is mutual dominant. 

### Relaxed comparison
A comparison may be *relaxed*, that is, a slack or delta is added on one side of the comparison to make it easier for a value to dominate. Let say _cost_ is one of the criteria in the vector, but we want to accept not only pareto optimal paths, but all paths that have a cost that is only 10% worse. We can do so by using a comparison like this `c' <= 1.10 * c"`. If we use this 10% slack the value 10 dominates 11: `10 <= 11 * 1.1`. But, also 11 dominates 10, because: `11 <= 10 * 1.1`. 

# The _API_ package
The API package define the programming API for this component. The implementation can only talk to the ouside world using the interface defined in the API package. 

## API _request_ package
The package containing all classes that represent the request sent to raptor. The `RequestBuilder` is a mutable version used to create a request. This can be passed around to build the request and the request is validated when it is `build()`, turned into a immutable `RangeRaptorRequest`. The builder is also used internally to mutate a request. One example is to change the request to calculate heuristics using StdRR, before running the _real_ search with a McRR.

The _request_ package also contain the `TuningParameters` interface. This interface define input parameters which should be set globally, not part of a trip planning request. You should carefully tune these parameters to fit your needs and your transit data.

To Raptor all input, the `RangeRaptorRequest` and the `TuningParameters` are treated as dynamic and no pre-computation is performed. This allowes for the `TuningParameters` to change at runtime.

## API _path_ package
A set of _paths_ is what Raptor returns after a successful search. 

## API _debug_ package
The Raptor search may also notify the caller about _StopArrivals_ events. This is used to debug a travel search, and is very usful if you want to understand way a path is returned or not. To use this you need to pass inn some debug event listeners and which events you would like to subscribe to. You may subscribe to events for a particular path or a set of stops.

## API _transit_ package
The Raptor need to access transit data as part of its search. The interface to the transit model is defined in this package and consist of Java interfaces only. The caller must implement these. The interfaces are defined to allow the implementation to be as efficient as possible. 

# The _rangeraptor_ package

## Worker
The different workers implement the Range Raptor algorithm. There is a common abstract worker and a specific one for McRR, StdRR and one we call BestTimeRangeRaptor. The workers have the main logic to perform the algorithm, but not the state to track the result. The result state is kept in the `WorkerState`, only variables to keep track of the algorithm progress is part of the worker.   

## WorkerState
The Worker State is the top level representation of the current search state and final result. Range Raptor store _StopArrivals_ to keep track of state. There are different worker state to achieve different goals. For example there is the `McRangeRaptorWorkerState`, the `BestTimesWorkerState`, the `StdRangeRaptorWorkerState`, the `CalculateHeuristicWorkerState` all used to calculate different things.

## _Standard_ package
Contain all workers and state classes used to implement Standard Range Raptor.

## _Multi-criteria_ package
Contain all workers and state classes used to implement the Multi-criteria Range Raptor.

## View package
The view package define a common interface for all states to implement so we can navigate in the state to create paths and debug information with the same mappers and debug handlers. At the same time, it allows the state objects to be organised in the most efficient way to give the best performance possible.

Paths and debug information is only created for a small subset of the state, so performance is not a big issue.

## Transit package
The _transit_ package contain common code used buy all worker and state objects. 
- It proved a forward and reverse calculator - used to switch a worker from searching forward to searching in reverse (Search from the destination to origin).
- It provide two trip schedule search classes, one for board search and one alight search.
- It also provide a cost calculator to be used with the multi-criteria search.

# Domain entities
By domain entities we mean the most important building blocks in Raptor. 

## Origin & Destination
We use _origin_ and _destination_ to talk about the _request_ start and end location. The origin is where the journey start and the destination is were it ends. This is also true for a _reverse_ search. We say that a reverse search - search from the _destination_ towards the _origin_. There is an exception to this witch is important to know about. In the context of a worker or its state, the origin is were the search start and the destination is were it ends - independently or the direction of the search. This is because the worker and state do not know if the search is a forward search or a reverse search.

## Stop
We only have one kind of Stop in Raptor - the location were a transit vehicle board and alight.  

## Stop Arrival
The main thing We keep track of in Raptor is StopArrivals. A StopArrival contain information about the previous StopArrival and arrival time, and how we got there; by transit or transfer. 

## Departure, Arrival, Board and Alight
We use _departure_ and _arrival_ to describe when we arrive or depart to/from a _stop_, while we talk about the "same thing" only relative to a TripSchedule as board and alight. _Board time_ means when we board a given TripsSchedule and departure time means when we depart from a given stop - both represent the same thing. The state keep track of `StopArrival` so the preferred expresion is usually arrive and depart, but in the `TripScheduleSearch` it make more sense to talk about board and alight.

## Pattern & TripSchedule (Trip)
To Raptor a _Pattern_ is a set of _TripSchedules_ visiting the same sequence of stops. Each "trip" is called a _TripSchedule_ and consist of arrival and departure times for the stops defined by the associated Pattern.

Sometimes we use the short term "trip" instead of TripSchedule. We do NOT use "trip" to refer to a journey from the origin to the destination, that is a path.  

## _Path_ (Itinerary and Journey) 
In this context, Path, Itinerary and Journey are almost the same. We use *Path* to talk about the minimum set of data returned by Raptor, then these paths are decorated with more information and returned to the end user as Itineraries. We avoid using _trip_ to describe a path. See TripSchedule below.

## Access, Egress, Transit and Transfer Leg
A _leg_ is a part of a _path_ from the origin to a stop, from a stop to another stop, or from a  stop to the destination. In the Raptor search we do not use legs, we use stop arrivals - we can compute paths with legs from the stop arrivals. So legs are used in the input and output of Raptor, but the main state focus on stop arrivals. 

The first leg in a path from origin to the first stop is the _access leg_. Likewise is the last leg from the last stop to the destination the _egress leg_. _Transit_ and _transfer_ legs are _intermediate_ legs between stops. A _transit leg_ is riding a transit vehicle, while a _transfer_ leg in most cases is walking between to stops. But the algorithm is not limited to walking, any none scheduled transport mode can be used. But for the algorithm to be effective, the transfer leg should be pre-calculated.  

