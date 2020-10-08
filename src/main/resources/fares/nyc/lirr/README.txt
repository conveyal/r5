# LIRR Fares

This directory contains fare information for the LIRR, used to construct LIRRTransferAllowances for the NYCInRoutingFareCalculator.

- lirr_zonal_fares.csv contains the fares for zone-to-zone journeys that do not require a change in direction. 
- via_fares.csv contains the fares for station-to-station journeys that do require a change in direction, but where LIRR will sell you a special "via" ticket.
- descendants.csv contains all the stations that can be reached from a given station using only inbound or outbound trains, and is used to determin transfer allowances since we assume that same-direction transfers are free.
