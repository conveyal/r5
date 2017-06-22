package com.conveyal.r5.labeling;

import com.conveyal.osmlib.Way;
import com.conveyal.r5.point_to_point.builder.SpeedConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.measure.converter.UnitConverter;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static javax.measure.unit.NonSI.KILOMETERS_PER_HOUR;
import static javax.measure.unit.NonSI.KNOT;
import static javax.measure.unit.NonSI.MILES_PER_HOUR;
import static javax.measure.unit.SI.METERS_PER_SECOND;

/**
 * Gets information about max speeds based on highway tags from build-config
 * And for each way reads maxspeed tags and returns max speeds.
 * If maxspeed isn't specified then uses highway tags. Otherwise returns default max speed.
 */
public class SpeedLabeler {
    private static final Logger LOG = LoggerFactory.getLogger(SpeedLabeler.class);

    // regex is an edited version of one taken from http://wiki.openstreetmap.org/wiki/Key:maxspeed
    private static final Pattern maxSpeedPattern = Pattern.compile("^([0-9][\\.0-9]+?)(?:[ ]?(kmh|km/h|kmph|kph|mph|knots))?$");

    private static Map<String, Float> highwaySpeedMap; // FIXME this is probably not supposed to be static.
    private Float defaultSpeed;

    public SpeedLabeler(SpeedConfig speedConfig) {
        //Converts all speeds from units to m/s
        UnitConverter unitConverter = null;
        //TODO: move this to SpeedConfig?
        switch (speedConfig.units) {
        case KMH:
            unitConverter = KILOMETERS_PER_HOUR.getConverterTo(METERS_PER_SECOND);
            break;
        case MPH:
            unitConverter = MILES_PER_HOUR.getConverterTo(METERS_PER_SECOND);
            break;
        case KNOTS:
            unitConverter = KNOT.getConverterTo(METERS_PER_SECOND);
            break;
        }
        highwaySpeedMap = new HashMap<>(speedConfig.values.size());
        //TODO: add validation so that this could assume correct tags
        for (Map.Entry<String, Integer> highwaySpeed: speedConfig.values.entrySet()) {
            highwaySpeedMap.put(highwaySpeed.getKey(),
                (float) unitConverter.convert(highwaySpeed.getValue()));
        }
        defaultSpeed = (float) unitConverter.convert(speedConfig.defaultSpeed);
    }

    /**
     * @return maxspeed in m/s, looking first at maxspeed tags and falling back on highway type heuristics
     */
    public float getSpeedMS(Way way, boolean back) {
        // first, check for maxspeed tags
        Float speed = null;
        Float currentSpeed;

        if (way.hasTag("maxspeed:motorcar"))
            speed = getMetersSecondFromSpeed(way.getTag("maxspeed:motorcar"));

        if (speed == null && !back && way.hasTag("maxspeed:forward"))
            speed = getMetersSecondFromSpeed(way.getTag("maxspeed:forward"));

        if (speed == null && back && way.hasTag("maxspeed:reverse"))
            speed = getMetersSecondFromSpeed(way.getTag("maxspeed:reverse"));

        if (speed == null && way.hasTag("maxspeed:lanes")) {
            for (String lane : way.getTag("maxspeed:lanes").split("\\|")) {
                currentSpeed = getMetersSecondFromSpeed(lane);
                // Pick the largest speed from the tag
                // currentSpeed might be null if it was invalid, for instance 10|fast|20
                if (currentSpeed != null && (speed == null || currentSpeed > speed))
                    speed = currentSpeed;
            }
        }

        if (way.hasTag("maxspeed") && speed == null)
            speed = getMetersSecondFromSpeed(way.getTag("maxspeed"));

        // this would be bad, as the segment could never be traversed by an automobile
        // The small epsilon is to account for possible rounding errors
        if (speed != null && speed < 0.0001)
            LOG.warn("Zero or negative automobile speed detected at {} based on OSM " +
                "maxspeed tags; ignoring these tags", this);

        // if there was a defined speed and it's not 0, we're done
        if (speed != null)
            return speed;

        if (way.getTag("highway") != null) {
            String highwayType = way.getTag("highway").toLowerCase().trim();
            return highwaySpeedMap.getOrDefault(highwayType, defaultSpeed);
        } else {
            //TODO: figure out what kind of roads are without highway tags
            return defaultSpeed;
        }
    }

    /**
     * Returns speed in ms from text speed from OSM
     *
     * @author Matt Conway
     * @param speed
     * @return
     */
    private Float getMetersSecondFromSpeed(String speed) {
        Matcher m = maxSpeedPattern.matcher(speed);
        if (!m.matches())
            return null;

        float originalUnits;
        try {
            originalUnits = (float) Double.parseDouble(m.group(1));
        } catch (NumberFormatException e) {
            LOG.warn("Could not parse max speed {}", m.group(1));
            return null;
        }

        String units = m.group(2);
        if (units == null || units.equals(""))
            units = "kmh";

        // we'll be doing quite a few string comparisons here
        units = units.intern();

        float metersSecond;

        if (units == "kmh" || units == "km/h" || units == "kmph" || units == "kph")
            metersSecond = 0.277778f * originalUnits;
        else if (units == "mph")
            metersSecond = 0.446944f * originalUnits;
        else if (units == "knots")
            metersSecond = 0.514444f * originalUnits;
        else
            return null;

        return metersSecond;
    }
}
