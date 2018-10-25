package com.conveyal.r5.profile.entur.util.paretoset;

public interface ParetoSortable {
    int paretoValue1();
    default int paretoValue2() {
        return 0;
    }
    default int paretoValue3() {
        return 0;
    }
    default int paretoValue4() {
        return 0;
    }
    default int paretoValue5() {
        return 0;
    }
    default int paretoValue6() {
        return 0;
    }
    default int paretoValue7() {
        return 0;
    }
    default int paretoValue8() {
        return 0;
    }
}
