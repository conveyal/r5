package com.conveyal.r5.api.util;

import java.time.ZonedDateTime;

/**
 * Simple alert
 */
public class Alert {

    //TODO: localization etc.

    //Header of alert if it exists
    public String alertHeaderText;

    /**
     * Long description of alert notnull
     */
    public String alertDescriptionText;

    //Url with more information
    public String alertUrl;

    //When this alerts comes into effect
    public ZonedDateTime effectiveStartDate;
    public ZonedDateTime effectiveEndDate;
}
