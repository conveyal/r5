package com.conveyal.analysis;

import com.conveyal.analysis.components.Components;
import com.conveyal.analysis.models.AddTripPattern;
import com.conveyal.analysis.models.Project;
import com.conveyal.analysis.models.Region;
import com.conveyal.analysis.persistence.Persistence;
import com.mongodb.MongoClient;
import com.mongodb.client.MongoDatabase;
import org.junit.BeforeClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;

import static com.conveyal.analysis.components.LocalAuthentication.LOCAL_GROUP;

/**
 * A few tests of some basic routes of the server.
 * This relies on the configuration file in the project root being a local (offline=true) configuration.
 */
public class AnalysisServerTest {
    private static final Logger LOG = LoggerFactory.getLogger(AnalysisServerTest.class);
    private static boolean setUpIsDone = false;

    /**
     * Prepare and start a testing-specific web server
     * @throws Exception
     */
    @BeforeClass
    public static void setUp() {
        if (setUpIsDone) {
            return;
        }

        Components components = new TestComponents();

        // drop the database to start fresh
        LOG.info("dropping test database");
        MongoClient mongoClient = new MongoClient();
        MongoDatabase db = mongoClient.getDatabase(components.config.databaseName());
        db.drop();

        // Start a server using the test Components
        BackendMain.startServer(components);

        // load database with some sample data
        LOG.info("loading test database with sample data");
        String accessGroup = LOCAL_GROUP;

        Region regionWithProjects = new Region();
        regionWithProjects.accessGroup = accessGroup;
        regionWithProjects.name = "test-region-with-projects";

        Persistence.regions.create(regionWithProjects);

        Region regionWithNoProjects = new Region();
        regionWithNoProjects.accessGroup = accessGroup;
        regionWithNoProjects.name = "test-region-with-no-projects";

        Persistence.regions.create(regionWithNoProjects);

        Project projectWithModications = new Project();
        projectWithModications.accessGroup = accessGroup;
        projectWithModications.name = "project-with-modifications";
        projectWithModications.regionId = regionWithProjects._id;

        Persistence.projects.create(projectWithModications);

        Project projectWithNoModications = new Project();
        projectWithNoModications.accessGroup = accessGroup;
        projectWithNoModications.name = "project-with-no-modifications";
        projectWithNoModications.regionId = regionWithProjects._id;

        Persistence.projects.create(projectWithNoModications);

        AddTripPattern modificationWithTimetables = new AddTripPattern();
        modificationWithTimetables.accessGroup = accessGroup;
        modificationWithTimetables.name = "modification-with-timetables";
        modificationWithTimetables.projectId = projectWithModications._id;
        AddTripPattern.Timetable timetable = new AddTripPattern.Timetable();
        timetable.name = "weekday";
        timetable.startTime = 12345;
        timetable.endTime = 23456;
        timetable.headwaySecs = 1234;
        timetable.monday = true;
        timetable.tuesday = true;
        timetable.wednesday = true;
        timetable.thursday = true;
        timetable.friday = true;
        timetable.saturday = false;
        timetable.sunday = false;
        modificationWithTimetables.timetables = Arrays.asList(timetable);

        Persistence.modifications.create(modificationWithTimetables);

        AddTripPattern modificationWithNoTimetables = new AddTripPattern();
        modificationWithNoTimetables.accessGroup = accessGroup;
        modificationWithNoTimetables.name = "modification-with-no-timetables";
        modificationWithNoTimetables.projectId = projectWithModications._id;
        modificationWithNoTimetables.timetables = new ArrayList<>();

        Persistence.modifications.create(modificationWithNoTimetables);

        setUpIsDone = true;
    }
}
