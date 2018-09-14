package com.conveyal.r5.profile.entur.api;

public interface Path2 {

    PathLeg accessLeg();

    Iterable<? extends PathLeg> legs();

    PathLeg egressLeg();

}
