/* This program is free software: you can redistribute it and/or
 modify it under the terms of the GNU Lesser General Public License
 as published by the Free Software Foundation, either version 3 of
 the License, or (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>. */

package com.conveyal.r5.otp2.speed_test.api.model;

import javax.xml.bind.annotation.XmlType;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Set;

@XmlType(name="TraverseMode")  
public enum TraverseMode {
    WALK, BICYCLE, CAR,
    TRAM, SUBWAY, RAIL, BUS, FERRY,
    CABLE_CAR, GONDOLA, FUNICULAR,
    TRANSIT, LEG_SWITCH,
    AIRPLANE, CAR_PARK, CAR_PICKUP;

    private static HashMap <Set<TraverseMode>, Set<TraverseMode>> setMap = 
            new HashMap <Set<TraverseMode>, Set<TraverseMode>>();

    public static Set<TraverseMode> internSet (Set<TraverseMode> modeSet) {
        if (modeSet == null)
            return null;
        Set<TraverseMode> ret = setMap.get(modeSet);
        if (ret == null) {
            EnumSet<TraverseMode> backingSet = EnumSet.noneOf(TraverseMode.class);
            backingSet.addAll(modeSet);
            Set<TraverseMode> unmodifiableSet = Collections.unmodifiableSet(backingSet);
            setMap.put(unmodifiableSet, unmodifiableSet);
            ret = unmodifiableSet;
        }
        return ret;
    }

    public boolean isTransit() {
        return this == TRAM || this == SUBWAY || this == RAIL || this == BUS || this == FERRY
                || this == CABLE_CAR || this == GONDOLA || this == FUNICULAR || this == TRANSIT
                || this == AIRPLANE;
    }

    public boolean isOnStreetNonTransit() {
        return this == WALK || this == BICYCLE || this == CAR;
    }
    
    public boolean isDriving() {
        return this == CAR;
    }

}
