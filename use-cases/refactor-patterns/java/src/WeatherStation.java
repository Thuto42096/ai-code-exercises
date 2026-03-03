import java.util.ArrayList;
import java.util.List;

/**
 * Concrete Subject in the Observer pattern.
 *
 * WeatherStation owns the authoritative weather measurements. When measurements
 * change via {@link #setMeasurements}, it notifies every registered
 * {@link WeatherObserver} automatically.  Observers (displays) are managed
 * dynamically — they can be added or removed at runtime without touching this
 * class.
 *
 * <p>Before refactoring, this class hard-coded calls to four private
 * update methods and maintained its own display-update buffer.  After
 * applying the Observer pattern:
 * <ul>
 *   <li>Display logic lives entirely inside the concrete observer classes.</li>
 *   <li>Adding a new display requires zero changes to WeatherStation.</li>
 *   <li>Observers can be registered/unregistered while the station is running.</li>
 * </ul>
 */
public class WeatherStation implements WeatherSubject {

    private float temperature;
    private float humidity;
    private float pressure;

    /** Dynamically managed list of registered observers. */
    private final List<WeatherObserver> observers;

    public WeatherStation() {
        this.observers = new ArrayList<>();
    }

    // -------------------------------------------------------------------------
    // WeatherSubject implementation
    // -------------------------------------------------------------------------

    @Override
    public void registerObserver(WeatherObserver observer) {
        if (observer != null && !observers.contains(observer)) {
            observers.add(observer);
        }
    }

    @Override
    public void removeObserver(WeatherObserver observer) {
        observers.remove(observer);
    }

    @Override
    public void notifyObservers() {
        for (WeatherObserver observer : observers) {
            observer.update(temperature, humidity, pressure);
        }
    }

    // -------------------------------------------------------------------------
    // Domain logic
    // -------------------------------------------------------------------------

    /**
     * Record new sensor readings and immediately notify all registered observers.
     *
     * @param temperature current temperature in °F
     * @param humidity    current relative humidity (%)
     * @param pressure    current barometric pressure (inHg)
     */
    public void setMeasurements(float temperature, float humidity, float pressure) {
        this.temperature = temperature;
        this.humidity    = humidity;
        this.pressure    = pressure;
        notifyObservers();
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public float getTemperature() { return temperature; }
    public float getHumidity()    { return humidity; }
    public float getPressure()    { return pressure; }

    // -------------------------------------------------------------------------
    // Example entry-point
    // -------------------------------------------------------------------------

    public static void main(String[] args) {
        WeatherStation station = new WeatherStation();

        // Register observers — zero changes to WeatherStation required
        station.registerObserver(new CurrentConditionsDisplay());
        station.registerObserver(new StatisticsDisplay());
        station.registerObserver(new ForecastDisplay());
        station.registerObserver(new HeatIndexDisplay());

        System.out.println("--- Weather Update 1 ---");
        station.setMeasurements(80.0f, 65.0f, 30.4f);

        System.out.println("\n--- Weather Update 2 ---");
        station.setMeasurements(82.0f, 70.0f, 29.2f);

        System.out.println("\n--- Weather Update 3 ---");
        station.setMeasurements(78.0f, 90.0f, 29.2f);
    }
}