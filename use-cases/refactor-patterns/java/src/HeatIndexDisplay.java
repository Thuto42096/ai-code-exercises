/**
 * Displays a simple heat-index value derived from temperature and humidity.
 *
 * The formula used here matches the original WeatherStation implementation
 * ((temperature + humidity) / 2) so that existing test assertions remain valid.
 * A real implementation would use the Rothfusz regression equation.
 */
public class HeatIndexDisplay implements WeatherObserver, DisplayElement {

    private float temperature;
    private float humidity;

    /**
     * Receives the latest measurements and prints the heat-index panel.
     */
    @Override
    public void update(float temperature, float humidity, float pressure) {
        this.temperature = temperature;
        this.humidity = humidity;
        System.out.println(display());
    }

    /**
     * Returns a formatted string containing the computed heat index.
     */
    @Override
    public String display() {
        float heatIndex = (temperature + humidity) / 2;
        return String.format("Heat index: %.1f", heatIndex);
    }
}

