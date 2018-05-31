package com.conveyal.r5.speed_test.api;


import com.conveyal.r5.speed_test.SpeedTest;
import com.fasterxml.jackson.jaxrs.json.JacksonJsonProvider;
import com.google.common.collect.Sets;
import org.glassfish.hk2.utilities.Binder;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;
import java.util.Set;

import static com.google.common.collect.Sets.newHashSet;

public class SpeedTestApplication extends Application {
    private static final Logger LOG = LoggerFactory.getLogger(SpeedTestApplication.class);

    private SpeedTest speedTestSingelton;

    public SpeedTestApplication(SpeedTest speedTestSingelton) {
        this.speedTestSingelton = speedTestSingelton;
    }

    @Override
    public Set<Class<?>> getClasses() {
        return newHashSet(PlannerResource.class);
    }

    @Override
    public Set<Object> getSingletons() {
        return Sets.newHashSet (
                new OTPExceptionMapper(),
                // Enable Jackson JSON response serialization
                new JacksonJsonProvider(),
                // Enable Jackson XML response serialization
                //new JacksonXMLProvider(),
                // Serialize POJOs (unannotated) JSON using Jackson
                // new JSONObjectMapperProvider(),
                // Allow injecting the OTP server object into Jersey resource classes
                speedTestBinder(),
                speedTestSingelton
        );
    }

    private Binder speedTestBinder() {
        return new AbstractBinder() {
            @Override
            protected void configure() {
                bind(speedTestSingelton).to(SpeedTest.class);
            }
        };
    }

    private static ExceptionMapper<Exception> errorHandler() {
        return e -> {
            LOG.error("Unhandled exception", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(e.toString() + " " + e.getMessage())
                    .type("text/plain").build();
        };
    }

    @Provider
    public static class OTPExceptionMapper implements ExceptionMapper<Exception> {
        private static final Logger LOG = LoggerFactory.getLogger(OTPExceptionMapper.class);

        public Response toResponse(Exception ex) {
            // Show the exception in the server log
            LOG.error("Unhandled exception", ex);

            int statusCode = Response.Status.INTERNAL_SERVER_ERROR.getStatusCode();
            if (ex instanceof WebApplicationException)
                statusCode = ((WebApplicationException)ex).getResponse().getStatus();

            // Return the short form message to the client
            return Response.status(statusCode)
                    .entity(ex.toString() + " " + ex.getMessage())
                    .type("text/plain").build();
        }
    }
}
