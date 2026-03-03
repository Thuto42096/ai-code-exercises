/**
 * Observer interface for the Weather Station Observer pattern.
 *
 * Any display element that wants to receive weather updates must implement
 * this interface. The Subject (WeatherStation) calls update() on every
 * registered observer whenever measurements change.
 */
public interface WeatherObserver {
    /**
     * Called by the subject when new measurements are available.
     *
     * @param temperature current temperature in °F
     * @param humidity    current relative humidity (%)
     * @param pressure    current barometric pressure (inHg)
     */
    void update(float temperature, float humidity, float pressure);
}

