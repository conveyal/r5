package com.conveyal.r5.speed_test.api;


import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.Application;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import java.util.Set;

import static com.google.common.collect.Sets.newHashSet;

public class SpeedTestApplication extends Application {
    private static final Logger LOG = LoggerFactory.getLogger(SpeedTestApplication.class);

    @Override
    public Set<Class<?>> getClasses() {
        return newHashSet(PlannerResource.class);
    }

    @Override
    public Set<Object> getSingletons() {
        return Sets.newHashSet (
                // errorHandler()
                // Enable Jackson JSON response serialization
                // new JacksonJsonProvider()
                // Enable Jackson XML response serialization
                //new JacksonXMLProvider(),
                // Serialize POJOs (unannotated) JSON using Jackson
                //new JSONObjectMapperProvider(),
                // Allow injecting the OTP server object into Jersey resource classes
                // server.makeBinder()
        );
    }

    private static ExceptionMapper<Exception> errorHandler() {
        return e -> {
            LOG.error("Unhandled exception", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(e.toString() + " " + e.getMessage())
                    .type("text/plain").build();
        };
    }
}
