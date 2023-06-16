package com.conveyal.analysis.controllers;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.analysis.UserPermissions;
import com.conveyal.analysis.components.TaskScheduler;
import com.conveyal.r5.analyst.progress.ApiTask;
import com.conveyal.r5.analyst.progress.Task;
import com.google.common.collect.ImmutableMap;
import spark.Request;
import spark.Response;
import spark.Service;

import java.util.List;

import static com.conveyal.analysis.util.JsonUtil.toJson;

/**
 * Provides a lightweight heartbeat endpoint by which the UI signals that a user is active, and the backend reports
 * any progress and status updates for that user. To determine which users are active we can't depend purely on backend
 * API requests, because many UI requests read and write straight to Mongo via Next lambda functions.
 *
 * The UI is expected to poll this endpoint at least once every 30 seconds whenever its tab is focused and the user
 * appears to be active, and whenever the user becomes active after a period of inactivity. Any user who has not hit
 * this endpoint for over 30 seconds is considered idle.
 *
 * The backend response will contain a list of all asynchronous tasks it is currently handling for the user.
 * Once a task is finished, the next time it is fetched it will be cleared from the backend. When the user is clearly
 * waiting for a task to finish, the UI may poll this endpoint more frequently to get smoother progress updates.
 *
 * Created by abyrd on 2021-03-03
 */
public class UserActivityController implements HttpController {

    private final TaskScheduler taskScheduler;

    public UserActivityController (TaskScheduler taskScheduler) {
        this.taskScheduler = taskScheduler;
    }

    @Override
    public void registerEndpoints (Service sparkService) {
        sparkService.get("/api/activity", this::getActivity, toJson);
        sparkService.delete("/api/activity/:id", this::removeActivity, toJson);
    }

    private ResponseModel getActivity (Request req, Response res) {
        UserPermissions userPermissions = UserPermissions.from(req);
        ResponseModel responseModel = new ResponseModel();
        responseModel.systemStatusMessages = List.of();
        responseModel.taskBacklog = taskScheduler.getBacklog();
        boolean system = Boolean.parseBoolean(req.queryParams("system")); // false if param not present
        String user = system ? "SYSTEM" : userPermissions.email;
        responseModel.taskProgress = taskScheduler.getTasksForUser(user);
        return responseModel;
    }

    private Object removeActivity (Request req, Response res) {
        UserPermissions userPermissions = UserPermissions.from(req);
        String id = req.params("id");
        Task task = taskScheduler.getTaskForUser(userPermissions.email, id);
        // Check if task still exists before attempting to remove.
        if (task == null) {
            throw  AnalysisServerException.notFound("Task does not exist. It may have already been removed by another user.");
        }
        // Disallow removing active tasks via the API.
        if (task.state.equals(Task.State.ACTIVE)) {
            throw AnalysisServerException.badRequest("Cannot clear an active task.");
        }
        if (taskScheduler.removeTaskForUser(userPermissions.email, id)) {
            return ImmutableMap.of("message", "Successfully cleared task.");
        } else {
            throw AnalysisServerException.badRequest("Failed to clear task.");
        }
    }

    /** API model used only to structure activity JSON messages sent back to UI. */
    public static class ResponseModel {
        /** For example: "Server going down at 17:00 GMT for maintenance" or "Working to resolve known issue [link]." */
        public List<String> systemStatusMessages;
        /** Number of tasks in the queue until this user's start processing. Just a rough indicator of progress. */
        public int taskBacklog;
        /** List of tasks with percentage complete, current stage of progress, and any failures or error messages. */
        public List<ApiTask> taskProgress;
    }

}
