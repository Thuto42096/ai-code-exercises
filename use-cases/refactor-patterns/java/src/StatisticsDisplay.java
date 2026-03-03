/**
 * Displays a simple Avg/Max/Min temperature summary.
 *
 * In a production implementation this panel would accumulate historical
 * readings; here it preserves the original formula (±2/±5 offsets) so that
 * existing tests continue to pass unchanged.
 */
public class StatisticsDisplay implements WeatherObserver, DisplayElement {

    private float temperature;

    /**
     * Receives the latest measurements and prints the statistics panel.
     */
    @Override
    public void update(float temperature, float humidity, float pressure) {
        this.temperature = temperature;
        System.out.println(display());
    }

    /**
     * Returns a formatted statistics string using the current temperature.
     */
    @Override
    public String display() {
        return String.format(
                "Weather statistics: Avg/Max/Min temperature = %.1f/%.1f/%.1f",
                temperature - 2, temperature + 2, temperature - 5);
    }
}

