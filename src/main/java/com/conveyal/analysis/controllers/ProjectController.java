package com.conveyal.analysis.controllers;

import com.conveyal.analysis.models.AddTripPattern;
import com.conveyal.analysis.models.ConvertToFrequency;
import com.conveyal.analysis.models.Modification;
import com.conveyal.analysis.models.Project;
import com.conveyal.analysis.persistence.Persistence;
import com.mongodb.QueryBuilder;
import org.bson.types.ObjectId;
import spark.Request;
import spark.Response;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static com.conveyal.analysis.util.JsonUtil.toJson;

public class ProjectController implements HttpController {

    public ProjectController () {
        // NO COMPONENT DEPENDENCIES
        // Eventually persistence will be a component (AnalysisDatabase) instead of static.
    }

    private Project findById(Request req, Response res) {
        return Persistence.projects.findByIdFromRequestIfPermitted(req);
    }

    private Collection<Project> getAllProjects (Request req, Response res) {
        return Persistence.projects.findPermitted(
                QueryBuilder.start("regionId").is(req.params("region")).get(),
                req.attribute("accessGroup")
        );
    }

    private Project create(Request req, Response res) throws IOException {
        return Persistence.projects.createFromJSONRequest(req);
    }

    private Project update(Request req, Response res) throws IOException {
        return Persistence.projects.updateFromJSONRequest(req);
    }

    private Collection<Modification> modifications (Request req, Response res) {
        return Persistence.modifications.findPermitted(
                QueryBuilder.start("projectId").is(req.params("_id")).get(),
                req.attribute("accessGroup")
        );
    }

    private Collection<Modification> importModifications (Request req, Response res) {
        final String importId = req.params("_importId");
        final String newId = req.params("_id");
        final String accessGroup = req.attribute("accessGroup");
        final Project project = Persistence.projects.findByIdIfPermitted(newId, accessGroup);
        final Project importProject = Persistence.projects.findByIdIfPermitted(importId, accessGroup);
        final boolean bundlesAreNotEqual = !project.bundleId.equals(importProject.bundleId);

        QueryBuilder query = QueryBuilder.start("projectId").is(importId);
        if (bundlesAreNotEqual) {
            // Different bundle? Only copy add trip modifications
            query = query.and("type").is("add-trip-pattern");
        }
        final Collection<Modification> modifications = Persistence.modifications.findPermitted(query.get(), accessGroup);

        // This would be a lot easier if we just used the actual `_id`s and dealt with it elsewhere when searching. They
        // should be unique anyways. Hmmmmmmmmmmmm. Trade offs.
        // Need to make two passes to create all the pairs and rematch for phasing
        final Map<String, String> modificationIdPairs = new HashMap<>();
        final Map<String, String> timetableIdPairs = new HashMap<>();

        return modifications
                .stream()
                .map(modification -> {
                    String oldModificationId = modification._id;
                    Modification clone = Persistence.modifications.create(modification);
                    modificationIdPairs.put(oldModificationId, clone._id);

                    // Change the projectId, most important part!
                    clone.projectId = newId;

                    // Set `name` to include "(import)"
                    clone.name = clone.name + " (import)";

                    // Set `updatedBy` by manually, `createdBy` stays with the original author
                    clone.updatedBy = req.attribute("email");

                    // Matched up the phased entries and timetables
                    if (modification.getType().equals(AddTripPattern.type)) {
                        if (bundlesAreNotEqual) {
                            // Remove references to real stops in the old bundle
                            ((AddTripPattern) clone).segments.forEach(segment -> {
                                segment.fromStopId = null;
                                segment.toStopId = null;
                            });

                            // Remove all phasing
                            ((AddTripPattern) clone).timetables.forEach(tt -> {
                                tt.phaseFromTimetable = null;
                                tt.phaseAtStop = null;
                                tt.phaseFromStop = null;
                            });
                        }

                        ((AddTripPattern) clone).timetables.forEach(tt -> {
                            String oldTTId = tt._id;
                            tt._id = new ObjectId().toString();
                            timetableIdPairs.put(oldTTId, tt._id);
                        });
                    } else if (modification.getType().equals(ConvertToFrequency.type)) {
                        ((ConvertToFrequency) clone).entries.forEach(tt -> {
                            String oldTTId = tt._id;
                            tt._id = new ObjectId().toString();
                            timetableIdPairs.put(oldTTId, tt._id);
                        });
                    }

                    return clone;
                })
                .collect(Collectors.toList())
                .stream()
                .map(modification -> {
                    // A second pass is needed to map the phase pairs
                    if (modification.getType().equals(AddTripPattern.type)) {
                        ((AddTripPattern) modification).timetables.forEach(tt -> {
                            String pft = tt.phaseFromTimetable;
                            if (pft != null && pft.length() > 0) {
                                String[] pfts = pft.split(":");
                                tt.phaseFromTimetable = modificationIdPairs.get(pfts[0]) + ":" + timetableIdPairs.get(pfts[1]);
                            }
                        });
                    } else if (modification.getType().equals(ConvertToFrequency.type)) {
                        ((ConvertToFrequency) modification).entries.forEach(tt -> {
                            String pft = tt.phaseFromTimetable;
                            if (pft != null && pft.length() > 0) {
                                String[] pfts = pft.split(":");
                                tt.phaseFromTimetable = modificationIdPairs.get(pfts[0]) + ":" + timetableIdPairs.get(pfts[1]);
                            }
                        });
                    }

                    return Persistence.modifications.put(modification);
                })
                .collect(Collectors.toList());
    }

    private Project deleteProject (Request req, Response res) {
        return Persistence.projects.removeIfPermitted(req.params("_id"), req.attribute("accessGroup"));
    }

    public Collection<Project> getProjects (Request req, Response res) {
        return Persistence.projects.findPermittedForQuery(req);
    }

    @Override
    public void registerEndpoints (spark.Service sparkService) {
        sparkService.path("/api/project", () -> {
            sparkService.get("", this::getProjects, toJson);
            sparkService.get("/:_id", this::findById, toJson);
            sparkService.get("/:_id/modifications", this::modifications, toJson);
            sparkService.post("/:_id/import/:_importId", this::importModifications, toJson);
            sparkService.post("", this::create, toJson);
            sparkService.options("", (q, s) -> "");
            sparkService.put("/:_id", this::update, toJson);
            sparkService.delete("/:_id", this::deleteProject, toJson);
            sparkService.options("/:_id", (q, s) -> "");
        });
        // Note this one is under the /api/region path, not /api/project
        sparkService.get("/api/region/:region/projects", this::getAllProjects); // TODO response transformer?
    }

}
