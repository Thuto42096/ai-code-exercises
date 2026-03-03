/**
 * Displays the most recent temperature and humidity reading.
 *
 * Implements both WeatherObserver (to receive updates from the WeatherStation)
 * and DisplayElement (to format its output independently).
 */
public class CurrentConditionsDisplay implements WeatherObserver, DisplayElement {

    private float temperature;
    private float humidity;

    /**
     * Receives the latest measurements from the WeatherStation subject and
     * immediately prints the formatted panel to standard output.
     */
    @Override
    public void update(float temperature, float humidity, float pressure) {
        this.temperature = temperature;
        this.humidity = humidity;
        System.out.println(display());
    }

    /**
     * Returns a formatted string of the current temperature and humidity.
     */
    @Override
    public String display() {
        return String.format("Current conditions: %.1f\u00b0F, %.1f%% humidity",
                temperature, humidity);
    }
}

