package com.conveyal.analysis.controllers;

import com.conveyal.analysis.models.AbstractTimetable;
import com.conveyal.analysis.models.AddTripPattern;
import com.conveyal.analysis.models.ConvertToFrequency;
import com.conveyal.analysis.models.Modification;
import com.conveyal.analysis.persistence.Persistence;
import org.bson.types.ObjectId;
import spark.Request;
import spark.Response;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.conveyal.analysis.util.JsonUtil.toJson;

public class ModificationController implements HttpController {

    public ModificationController () {
        // NO COMPONENT DEPENDENCIES
        // Eventually Persistence will be a component (AnalysisDatabase) instead of static.
    }

    private Modification getModification (Request req, Response res) {
        return Persistence.modifications.findByIdFromRequestIfPermitted(req);
    }

    private Modification create (Request request, Response response) throws IOException {
        return Persistence.modifications.createFromJSONRequest(request);
    }

    private Modification update (Request request, Response response) throws IOException {
        return Persistence.modifications.updateFromJSONRequest(request);
    }

    private Modification deleteModification (Request req, Response res) {
        return Persistence.modifications.removeIfPermitted(req.params("_id"), req.attribute("accessGroup"));
    }

    private void mapPhaseIds (List<AbstractTimetable> timetables, String oldModificationId, String newModificationId) {
        Map<String, String> idPairs = new HashMap<String, String>();
        timetables.forEach(tt -> {
            String newId = ObjectId.get().toString();
            idPairs.put(tt._id, newId);
            tt._id = newId;
        });

        timetables
                .stream()
                .filter(tt -> tt.phaseFromTimetable != null && tt.phaseFromTimetable.length() > 0)
                .filter(tt -> tt.phaseFromTimetable.contains(oldModificationId))
                .forEach(tt -> {
                    String oldTTId = tt.phaseFromTimetable.split(":")[1];
                    tt.phaseFromTimetable = newModificationId + ":" + idPairs.get(oldTTId);
                });
    }

    private Modification copyModification (Request req, Response res) {
        Modification modification = Persistence.modifications.findByIdFromRequestIfPermitted(req);

        String oldId = modification._id;
        Modification clone = Persistence.modifications.create(modification);

        // Matched up the phased entries and timetables
        if (modification.getType().equals(AddTripPattern.type)) {
            mapPhaseIds((List<AbstractTimetable>)(List<?>)((AddTripPattern) clone).timetables, oldId, clone._id);
        } else if (modification.getType().equals(ConvertToFrequency.type)) {
            mapPhaseIds((List<AbstractTimetable>)(List<?>)((ConvertToFrequency) clone).entries, oldId, clone._id);
        }

        // Set `name` to include "(copy)"
        clone.name = clone.name + " (copy)";

        // Set `updateBy` manually, `createdBy` stays with the original modification author
        clone.updatedBy = req.attribute("email");

        // Update the clone
        return Persistence.modifications.put(clone);
    }

    @Override
    public void registerEndpoints (spark.Service sparkService) {
        sparkService.path("/api/modification", () -> {
            sparkService.get("/:_id", this::getModification, toJson);
            sparkService.post("/:_id/copy", this::copyModification, toJson);
            sparkService.post("", this::create, toJson);
            // Handle HTTP OPTIONS request to provide any configured CORS headers.
            sparkService.options("", (q, s) -> "");
            sparkService.put("/:_id", this::update, toJson);
            sparkService.options("/:_id", (q, s) -> "");
            sparkService.delete("/:_id", this::deleteModification, toJson);
        });
    }
}
