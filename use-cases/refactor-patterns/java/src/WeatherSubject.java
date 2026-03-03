/**
 * Subject interface for the Weather Station Observer pattern.
 *
 * Any data source (e.g. WeatherStation) that wants to broadcast changes to
 * interested parties must implement this interface.  Observers are managed
 * dynamically at runtime — they can be added or removed without touching the
 * subject's core measurement logic.
 */
public interface WeatherSubject {
    /**
     * Add an observer to the notification list.
     *
     * @param observer the observer to register; must not be null
     */
    void registerObserver(WeatherObserver observer);

    /**
     * Remove a previously registered observer.
     * Has no effect if the observer is not currently registered.
     *
     * @param observer the observer to remove
     */
    void removeObserver(WeatherObserver observer);

    /**
     * Push the current state to every registered observer.
     * Called automatically by the subject whenever its state changes.
     */
    void notifyObservers();
}

