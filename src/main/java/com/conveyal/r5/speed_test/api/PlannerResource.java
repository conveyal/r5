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
package com.conveyal.r5.speed_test.api;

import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.speed_test.SpeedTest;
import org.glassfish.grizzly.http.server.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.conveyal.r5.speed_test.api.model.*;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriInfo;

/**
 * This is the primary entry point for the trip planning web service.
 * All parameters are passed in the query string. These parameters are defined as fields in the abstract
 * RoutingResource superclass, which also has methods for building routing requests from query
 * parameters. This allows multiple web services to have the same set of query parameters.
 * In order for inheritance to work, the REST resources are request-scoped (constructed at each request)
 * rather than singleton-scoped (a single instance existing for the lifetime of the OTP server).
 */
@Path("routers/{routerId}/plan") // final element needed here rather than on method to distinguish from routers API
public class PlannerResource extends RoutingResource {

    private static final Logger LOG = LoggerFactory.getLogger(PlannerResource.class);

    // We inject info about the incoming request so we can include the incoming query
    // parameters in the outgoing response. This is a TriMet requirement.
    // Jersey uses @Context to inject internal types and @InjectParam or @Resource for DI objects.
    @GET
    @Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML, MediaType.TEXT_XML })
    public Response plan(@Context UriInfo uriInfo, @Context Request grizzlyRequest) {

        // Create response object, containing a copy of all request parameters. Maybe they should be in the debug section of the response.
        Response response = new Response(uriInfo);
        ProfileRequest request;
        try {
            request = super.buildRequest();

            SpeedTest speedTest = new SpeedTest();
            TripPlan plan = speedTest.route(request);
            response.setPlan(plan);
        } catch (Exception e) {
        }
        return response;
    }

}
