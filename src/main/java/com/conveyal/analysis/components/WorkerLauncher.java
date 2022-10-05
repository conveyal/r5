package com.conveyal.analysis.components;

import com.conveyal.components.Component;
import com.conveyal.worker.WorkerCategory;
import com.conveyal.worker.WorkerTags;

/** Interface for Components that start workers. */
public interface WorkerLauncher extends Component {

    /** Start worker instances to handle single point or regional tasks. */
    public void launch (WorkerCategory category, WorkerTags workerTags, int nOnDemand, int nSpot);

}
