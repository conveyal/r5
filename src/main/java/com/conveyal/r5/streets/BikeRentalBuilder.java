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
 * Capital bikeshare XML reader and bikeRental station builder
 */
public class BikeRentalBuilder {
    File file;

    public BikeRentalBuilder(File file) {
        this.file = file;
    }

    List<BikeRentalStation> getRentalStations() {
        List<BikeRentalStation> bikeRentalStations = new ArrayList<>();
        BikeRentalStation bikeRentalStation = null;

        XMLInputFactory xmlInputFactory = XMLInputFactory.newInstance();
        //This is needed so that ampersand <> are read from text nodes correctly
        xmlInputFactory.setProperty(XMLInputFactory.IS_COALESCING, true);
        try {
            XMLEventReader xmlEventReader = xmlInputFactory.createXMLEventReader(new FileInputStream(file));
            while(xmlEventReader.hasNext()) {
                XMLEvent xmlEvent = xmlEventReader.nextEvent();
                if (xmlEvent.isStartElement()) {
                    StartElement startElement = xmlEvent.asStartElement();
                    if (startElement.getName().getLocalPart().equals("station")) {
                        bikeRentalStation = new BikeRentalStation();
                        bikeRentalStation.networks = new HashSet<>(1);
                        bikeRentalStation.networks.add("Capital BikeShare Washington DC");
                    } else if(startElement.getName().getLocalPart().equals("id")) {
                        xmlEvent = xmlEventReader.nextEvent();
                        bikeRentalStation.id = xmlEvent.asCharacters().getData();
                    } else if (startElement.getName().getLocalPart().equals("name")) {
                        xmlEvent = xmlEventReader.nextEvent();
                        bikeRentalStation.name = xmlEvent.asCharacters().getData();
                    } else if (startElement.getName().getLocalPart().equals("lat")) {
                        xmlEvent = xmlEventReader.nextEvent();
                        bikeRentalStation.lat = Float.parseFloat(xmlEvent.asCharacters().getData());
                    } else if (startElement.getName().getLocalPart().equals("long")) {
                        xmlEvent = xmlEventReader.nextEvent();
                        bikeRentalStation.lon = Float.parseFloat(xmlEvent.asCharacters().getData());
                    } else if (startElement.getName().getLocalPart().equals("nbBikes")) {
                        xmlEvent = xmlEventReader.nextEvent();
                        bikeRentalStation.bikesAvailable = Integer.parseInt(xmlEvent.asCharacters().getData());
                    } else if (startElement.getName().getLocalPart().equals("nbEmptyDocks")) {
                        xmlEvent = xmlEventReader.nextEvent();
                        bikeRentalStation.spacesAvailable = Integer.parseInt(xmlEvent.asCharacters().getData());
                    }
                }
                if (xmlEvent.isEndElement()) {
                    EndElement endElement = xmlEvent.asEndElement();
                    if (endElement.getName().getLocalPart().equals("station")) {
                        bikeRentalStations.add(bikeRentalStation);
                    }
                }
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (XMLStreamException e) {
            e.printStackTrace();
        }

        return bikeRentalStations;
    }
}
