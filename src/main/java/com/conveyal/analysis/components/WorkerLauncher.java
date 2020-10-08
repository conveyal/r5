package com.conveyal.analysis.components;

import com.conveyal.analysis.components.broker.WorkerTags;
import com.conveyal.r5.analyst.WorkerCategory;

/** Interface for Components that start workers. */
public interface WorkerLauncher extends Component {

    /** Start worker instances to handle single point or regional tasks. */
    public void launch (WorkerCategory category, WorkerTags workerTags, int nOnDemand, int nSpot);

}
