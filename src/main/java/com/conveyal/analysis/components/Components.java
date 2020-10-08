package com.conveyal.analysis.components;

import com.conveyal.analysis.BackendConfig;
import com.conveyal.analysis.components.broker.Broker;
import com.conveyal.analysis.components.eventbus.EventBus;
import com.conveyal.analysis.persistence.AnalysisDB;
import com.conveyal.file.FileStorage;
import com.conveyal.gtfs.GTFSCache;
import com.conveyal.r5.streets.OSMCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * We are adopting a lightweight dependency injection approach, where we manually wire up our components instead of
 * relying on a framework. For our simple case the approach is almost identical but we have to manage the order in
 * which the components are instantiated. This amounts to a manual depth-first traversal of the dependency graph which
 * is not prohibitive for a limited number of components.
 *
 * This class is like an "application context" in dependency injection frameworks, so could also be renamed "Context".
 * It keeps references to all components of the system in one place. These components are typically singleton
 * instances of classes. Making the components instances (rather than classes grouping together static fields and
 * methods) allows them to be replaced with other implementations, e.g. to switch between different authentication or
 * storage systems.
 *
 * Ideally we'd want these to be final fields, but that prevents us from setting them from non-constructor methods
 * including subclass constructors. Eventually they could be protected, but that's also not possible yet because for
 * the time being we still set up some static functionality and the HttpApi by peeking into the components instance.
 *
 * Really, outside code should never reference these component fields, and this class should be essentially unused
 * after application construction. Each component should hold final references to all the other components it needs,
 * and those references should be passed into the component's constructor by the wiring-up code.
 *
 * Some environment-specific bindings depend on some standard bindings and vice versa. Therefore it's not practical
 * to split into two methods, one instantiating shared standard bindings and one instantiating custom bindings.
 * We could instead define methods to provide a binding for each interface, but that seems like overkill
 * generalization. We only expect to ever have 2-3 variants of this class. Just make a new subclass and complete
 * constructor for each.
 */
public abstract class Components {

    private static final Logger LOG = LoggerFactory.getLogger(Components.class);

    public BackendConfig config;
    /** Verification of user identity and permissions. */
    public Authentication authentication;
    public FileStorage fileStorage;
    public GTFSCache gtfsCache;
    public OSMCache osmCache;
    /** System for processing incoming accessibility analysis requests. */
    public Compute compute = null;
    public WorkerLauncher workerLauncher;
    public Broker broker;
    // TODO  Unified persistence of Java objects within and between sessions (an abstraction for a database)
    // public static Persistence persistence;
    public AnalysisDB database;
    public HttpApi httpApi;
    public TaskScheduler taskScheduler;
    public EventBus eventBus;

}
