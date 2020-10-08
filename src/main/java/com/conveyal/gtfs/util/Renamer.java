package com.conveyal.gtfs.util;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Creates new unique names to give things new IDs.
 */
public class Renamer {

    private Map<String, String> newNameForOldName = new HashMap<>();
    private Map<String, String> oldNameForNewName = new HashMap<>();

    public String getNewName(String oldName) {
        String newName = newNameForOldName.get(oldName);
        if (newName == null) {
            while (newName == null || oldNameForNewName.containsKey(newName)) {
                newName = UUID.randomUUID().toString().substring(0, 6);
            }
            newNameForOldName.put(oldName, newName);
            oldNameForNewName.put(newName, oldName);
            System.out.println(oldName + " <--> " + newName);
        }
        return newName;
    }

}
