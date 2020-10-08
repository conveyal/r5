package com.conveyal.gtfs.validator.model;

import com.conveyal.gtfs.model.Stop;

import java.io.Serializable;

public class DuplicateStops implements Serializable {

	public Stop stop1;
	public Stop stop2;
	
	public double distance;
	
	public DuplicateStops(Stop s1, Stop s2, double dist) {
		stop1 = s1;
		stop2 = s2;
		distance = dist;
	}
	public Stop getOriginalStop () { return stop1.sourceFileLine < stop2.sourceFileLine ? stop1 : stop2; }

	public Stop getDuplicatedStop () { return stop1.sourceFileLine > stop2.sourceFileLine ? stop1 : stop2; }

	public String getStop1Id() {
		return stop1.stop_id;
	}
	
	public String getStop2Id() {
		return stop2.stop_id;
	}
	
	public String getStopIds() {
		return this.getStop1Id() + "," + this.getStop2Id();
	}
	
	public String toString() {
		return "Stops " + this.getStop1Id() + " and " +  this.getStop2Id() + " are within " + this.distance + " meters";
	}
}
