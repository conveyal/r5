package com.conveyal.analysis.datasource.derivation;

import com.conveyal.analysis.models.BaseModel;
import com.conveyal.analysis.models.DataGroup;
import com.conveyal.analysis.models.DataSource;
import com.conveyal.r5.analyst.progress.TaskAction;

import java.util.Collection;

/**
 * An interface for unary operators mapping DataSources into other data sets represented in our Mongo database.
 * An asynchronous function from D to M.
 */
public interface DataDerivation<D extends DataSource, M extends BaseModel> extends TaskAction {

    public D dataSource ();

//    public Collection<M> outputs();

//    public DataGroup outputGroup();

//    or single output method: public DataGroup<M> output();

}
