package com.conveyal.analysis.components;

/**
 * These are the top-level modules of our analysis backend that are instantiated and wired up to one another when the
 * application starts up. There is typically only one instance of each component, and all references to the component
 * are final.
 *
 * Each component should encapsulate a distinct, well-defined set of functionality. Different implementations of
 * components allow running locally or in other environments like AWS or potentially other cloud service providers.
 * Currently this is a marker interface with no methods, just to indicate the role of certain classes in the project.
 */
public interface Component {
}
