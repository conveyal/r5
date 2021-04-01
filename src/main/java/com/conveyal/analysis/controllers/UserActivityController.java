package com.conveyal.analysis.controllers;

import com.conveyal.analysis.UserPermissions;
import com.conveyal.analysis.components.TaskScheduler;
import com.conveyal.r5.analyst.progress.Task;
import spark.Request;
import spark.Response;
import spark.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.conveyal.analysis.components.HttpApi.USER_PERMISSIONS_ATTRIBUTE;
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
        sparkService.delete("/api/activity", this::clearAllCompletedTasks);
        sparkService.delete("/api/activity/:id", this::clearTask);
    }

    private ResponseModel getActivity (Request req, Response res) {
        UserPermissions userPermissions = req.attribute(USER_PERMISSIONS_ATTRIBUTE);
        ResponseModel responseModel = new ResponseModel();
        responseModel.taskProgress = taskScheduler.getTasksForUser(userPermissions);
        responseModel.taskBacklog = responseModel.taskProgress
            .stream()
            .filter(t -> t.state == Task.State.QUEUED)
            .count();
        return responseModel;
    }

    private boolean clearAllCompletedTasks (Request req, Response res) {
        UserPermissions user = req.attribute(USER_PERMISSIONS_ATTRIBUTE);
        taskScheduler.removeAllCompletedTasksForUser(user);
        return true;
    }

    private boolean clearTask (Request req, Response res) {
        UserPermissions user = req.attribute(USER_PERMISSIONS_ATTRIBUTE);
        return taskScheduler.removeTaskForUser(UUID.fromString(req.params("id")), user);
    }

    /** Only used to structure JSON messages sent back to UI. */
    public static class ResponseModel {
        /** For example: "Server going down at 17:00 GMT for maintenance" or "Working to resolve known issue [link]." */
        public List<String> systemStatusMessages = new ArrayList<>();
        /** Number of tasks in the queue until this user's start processing. Just a rough indicator of progress. */
        public long taskBacklog;
        /** Nested list of tasks with percentage complete and any failures or error messages. */
        public List<Task> taskProgress;
    }

}
