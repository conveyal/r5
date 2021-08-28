package com.conveyal.analysis.datasource;

import com.conveyal.r5.analyst.progress.ProgressListener;
import com.conveyal.r5.analyst.progress.WorkProduct;
import org.junit.jupiter.api.Assertions;

/**
 * A mock ProgressListener for use in tests, which makes sure all the interface methods are called and shows progress.
 */
public class TestingProgressListener implements ProgressListener {

    private String description;
    private WorkProduct workProduct;
    private int count = 0;

    @Override
    public void beginTask (String description, int totalElements) {
        this.description = description;
    }

    @Override
    public void increment (int n) {
        count += n;
    }

    @Override
    public void setWorkProduct (WorkProduct workProduct) {
        this.workProduct = workProduct;
    }

    public void assertUsedCorrectly () {
        Assertions.assertNotNull(description);
        Assertions.assertNotNull(workProduct);
        Assertions.assertTrue(count > 0);
    }

}
