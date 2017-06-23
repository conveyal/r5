package com.conveyal.r5.streets;

import com.conveyal.r5.api.util.BikeRentalStation;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.EndElement;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * This used to load capital bikeshare XML from a file.
 * TODO implement loading GBFS from a URL
 */
public class BikeRentalBuilder {

    File file;

    public BikeRentalBuilder(File file) {
        this.file = file;
    }

    List<BikeRentalStation> getRentalStations() {
        throw new UnsupportedOperationException("IMPLEMENT GBFS!");
    }
}
