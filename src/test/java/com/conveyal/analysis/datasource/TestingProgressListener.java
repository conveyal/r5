package com.conveyal.analysis.datasource;

import com.conveyal.r5.analyst.progress.ProgressListener;
import com.conveyal.r5.analyst.progress.WorkProduct;
import org.junit.jupiter.api.Assertions;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A mock ProgressListener for use in tests, which makes sure all the interface methods are called and shows progress.
 */
public class TestingProgressListener implements ProgressListener {

    private String description;
    private WorkProduct workProduct;
    private int taskCount = 0;
    private int totalElements = 0;
    private int elementsCompleted = 0;

    @Override
    public void beginTask (String description, int totalElements) {
        this.description = description;
        this.totalElements = totalElements;
        taskCount += 1;
    }

    @Override
    public void increment (int n) {
        elementsCompleted += n;
        assertTrue(elementsCompleted <= totalElements);
    }

    @Override
    public void setWorkProduct (WorkProduct workProduct) {
        this.workProduct = workProduct;
    }

    public void assertUsedCorrectly () {
        assertNotNull(description);
        assertNotNull(workProduct);
        assertTrue(taskCount > 0);
        assertTrue(elementsCompleted > 0);
        assertEquals(totalElements, elementsCompleted);
    }

}
