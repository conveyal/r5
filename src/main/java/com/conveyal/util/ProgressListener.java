package com.conveyal.util;

public interface ProgressListener {

    void setTotalItems (int nTotal);

    void setCompletedItems(int nComplete);
}
