/**
 * Marker/capability interface for all weather display panels.
 *
 * Separating display rendering (this interface) from data reception
 * (WeatherObserver) follows the Interface Segregation Principle: an observer
 * that only needs to consume data doesn't have to expose a display() method,
 * and vice-versa.
 */
public interface DisplayElement {
    /**
     * Format and return the current display content as a human-readable string.
     *
     * @return a non-null string representing the panel's current state
     */
    String display();
}

