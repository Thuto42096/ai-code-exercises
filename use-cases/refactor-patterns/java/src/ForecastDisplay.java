/**
 * Displays a simple weather forecast based on barometric pressure.
 *
 * Pressures below 29.92 inHg indicate falling pressure (rain/cool weather);
 * pressures at or above indicate stable or rising conditions.
 */
public class ForecastDisplay implements WeatherObserver, DisplayElement {

    /** Standard threshold used in original WeatherStation implementation. */
    private static final float PRESSURE_THRESHOLD = 29.92f;

    private float pressure;

    /**
     * Receives the latest measurements and prints the forecast panel.
     */
    @Override
    public void update(float temperature, float humidity, float pressure) {
        this.pressure = pressure;
        System.out.println(display());
    }

    /**
     * Returns a forecast string based on the most recently received pressure.
     */
    @Override
    public String display() {
        String prediction = pressure < PRESSURE_THRESHOLD
                ? "Watch out for cooler, rainy weather"
                : "Improving weather on the way!";
        return "Forecast: " + prediction;
    }
}

