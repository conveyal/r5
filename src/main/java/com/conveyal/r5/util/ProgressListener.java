package com.conveyal.r5.util;

public interface ProgressListener {

    public void setTotalItems (int nTotal);

    public void setCompletedItems(int nComplete);

}
