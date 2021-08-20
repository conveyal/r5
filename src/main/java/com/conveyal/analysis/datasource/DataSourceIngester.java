package com.conveyal.analysis.datasource;

import com.conveyal.analysis.models.DataSource;
import com.conveyal.r5.analyst.progress.ProgressListener;

import java.io.File;

/**
 * Logic for loading and validating a specific kind of input file, yielding a specific subclass of DataSource.
 * This plugs into DataSourceUploadAction, which handles the general parts of processing any new DataSource.
 */
public abstract class DataSourceIngester <D extends DataSource> {

    /**
     * An accessor method that gives the general purpose DataSourceUploadAction a view of the DataSource being
     * constructed. This allows to DataSourceUploadAction to set all the shared general properties of a DataSource,
     * leaving the DataSourceIngester to handle only the details specific to its input format and DataSource subclass.
     * Concrete subclasses should ensure that this method can return an object immediately after they're constructed.
     * Or maybe only after ingest() returns?
     */
    public abstract D dataSource ();

    /**
     * This method is implemented on concrete subclasses to provide logic for interpreting a particular file type.
     * This is potentially the slowest part of DataSource creation so is called asynchronously (in a background task).
     * A single File is passed in here (rather than in the subclass constructors) because the file is moved into
     * storage before ingestion. Some supported formats (only shapefile for now) are made up of more than one file,
     * which must all be in the same directory. Moving them into storage ensures they're all in the same directory with
     * the same base name as required, and only one of their complete file names must be provided.
     */
    public abstract void ingest (File file, ProgressListener progressListener);

}
