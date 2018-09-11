package com.conveyal.r5.profile.mcrr.api;

public interface Path2 {

    PathLeg accessLeg();

    Iterable<? extends PathLeg> legs();

    PathLeg egressLeg();

}
