package com.conveyal.analysis.controllers;

import com.conveyal.analysis.models.AddTripPattern;
import com.conveyal.analysis.models.Modification;
import com.conveyal.analysis.models.Project;
import com.conveyal.analysis.models.Region;
import com.conveyal.analysis.persistence.Persistence;
import com.conveyal.analysis.util.JsonUtil;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mongodb.QueryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;

import java.util.Collection;
import java.util.List;

import static com.conveyal.analysis.util.JsonUtil.arrayNode;
import static com.conveyal.analysis.util.JsonUtil.objectNode;

/**
 * Created by evan siroky on 5/3/18.
 */
public class TimetableController implements HttpController {

    private static final Logger LOG = LoggerFactory.getLogger(TimetableController.class);

    public TimetableController () {
        // NO COMPONENT DEPENDENCIES
        // Eventually persistence will be a component (AnalysisDatabase) instead of static.
    }

    // Unlike many other methods, rather than serializing a POJO to JSON,
    // this builds up the JSON using the Jackson tree API.
    private String getTimetables (Request req, Response res) {
        Collection<Region> regions = Persistence.regions.findAllForRequest(req);

        ArrayNode json = JsonUtil.objectMapper.createArrayNode();
        for (Region region : regions) {
            ObjectNode r = objectNode();
            r.put("_id", region._id);
            r.put("name", region.name);
            ArrayNode regionProjects = arrayNode();
            List<Project> projects = Persistence.projects.find(QueryBuilder.start("regionId").is(region._id).get()).toArray();
            for (Project project : projects) {
                ObjectNode p = objectNode();
                p.put("_id", project._id);
                p.put("name", project.name);
                ArrayNode projectModifications = arrayNode();
                List<Modification> modifications = Persistence.modifications.find(
                        QueryBuilder.start("projectId").is(project._id).and("type").is("add-trip-pattern").get()
                ).toArray();
                for (Modification modification : modifications) {
                    AddTripPattern tripPattern = (AddTripPattern) modification;
                    ObjectNode m = objectNode();
                    m.put("_id", modification._id);
                    m.put("name", modification.name);
                    m.put("segments", JsonUtil.objectMapper.valueToTree(tripPattern.segments));
                    ArrayNode modificationTimetables = arrayNode();
                    for (AddTripPattern.Timetable timetable : tripPattern.timetables) {
                        modificationTimetables.add(JsonUtil.objectMapper.valueToTree(timetable));
                    }
                    m.put("timetables", modificationTimetables);
                    if (modificationTimetables.size() > 0) {
                        projectModifications.add(m);
                    }
                }
                p.put("modifications", projectModifications);
                if (projectModifications.size() > 0) {
                    regionProjects.add(p);
                }
            }
            r.put("projects", regionProjects);
            if (regionProjects.size() > 0) {
                json.add(r);
            }
        }
        return JsonUtil.toJsonString(json);
    }

    @Override
    public void registerEndpoints (spark.Service sparkService) {
        sparkService.get("/api/timetables", this::getTimetables);
    }
}
