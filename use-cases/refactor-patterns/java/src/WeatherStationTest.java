import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Observer-pattern refactoring of WeatherStation.
 *
 * Three groups of tests:
 *  1. Preserved behaviour — same output assertions as the original tests.
 *  2. Observer lifecycle — register, remove, duplicate guard.
 *  3. Individual display logic — each concrete observer verified in isolation.
 */
public class WeatherStationTest {

    private WeatherStation weatherStation;
    private final ByteArrayOutputStream outputCaptor = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;

    @BeforeEach
    void setUp() {
        weatherStation = new WeatherStation();
        System.setOut(new PrintStream(outputCaptor));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
    }

    // =========================================================================
    // Group 1 — Preserved behaviour (original test assertions kept intact)
    // =========================================================================

    @Test
    void testInitialValues() {
        assertEquals(0.0f, weatherStation.getTemperature());
        assertEquals(0.0f, weatherStation.getHumidity());
        assertEquals(0.0f, weatherStation.getPressure());
    }

    @Test
    void testSetMeasurementsUpdatesGetters() {
        weatherStation.setMeasurements(75.0f, 60.0f, 30.0f);

        assertEquals(75.0f, weatherStation.getTemperature());
        assertEquals(60.0f, weatherStation.getHumidity());
        assertEquals(30.0f, weatherStation.getPressure());
    }

    @Test
    void testAllDisplaysReceiveUpdate() {
        weatherStation.registerObserver(new CurrentConditionsDisplay());
        weatherStation.registerObserver(new StatisticsDisplay());
        weatherStation.registerObserver(new ForecastDisplay());
        weatherStation.registerObserver(new HeatIndexDisplay());

        weatherStation.setMeasurements(75.0f, 60.0f, 30.0f);
        String output = outputCaptor.toString();

        assertTrue(output.contains("Current conditions: 75.0\u00b0F, 60.0% humidity"));
        assertTrue(output.contains("Weather statistics: Avg/Max/Min temperature = 73.0/77.0/70.0"));
        assertTrue(output.contains("Forecast: Improving weather on the way!"));
        assertTrue(output.contains("Heat index: 67.5"));
    }

    @Test
    void testForecastWithLowPressure() {
        weatherStation.registerObserver(new ForecastDisplay());
        weatherStation.setMeasurements(75.0f, 60.0f, 29.5f);

        assertTrue(outputCaptor.toString().contains("Watch out for cooler, rainy weather"));
    }

    @Test
    void testForecastWithHighPressure() {
        weatherStation.registerObserver(new ForecastDisplay());
        weatherStation.setMeasurements(75.0f, 60.0f, 30.5f);

        assertTrue(outputCaptor.toString().contains("Improving weather on the way!"));
    }

    @Test
    void testHeatIndexCalculation() {
        weatherStation.registerObserver(new HeatIndexDisplay());
        weatherStation.setMeasurements(85.0f, 75.0f, 30.0f);

        assertTrue(outputCaptor.toString().contains("Heat index: 80.0"));
    }

    @Test
    void testMultipleUpdates() {
        weatherStation.registerObserver(new CurrentConditionsDisplay());
        weatherStation.registerObserver(new ForecastDisplay());

        weatherStation.setMeasurements(80.0f, 65.0f, 30.4f);
        outputCaptor.reset();

        weatherStation.setMeasurements(82.0f, 70.0f, 29.2f);
        String output = outputCaptor.toString();

        assertTrue(output.contains("Current conditions: 82.0\u00b0F, 70.0% humidity"));
        assertTrue(output.contains("Watch out for cooler, rainy weather"));
    }

    // =========================================================================
    // Group 2 — Observer lifecycle (new Observer-pattern-specific tests)
    // =========================================================================

    @Test
    void testNoOutputWithNoObserversRegistered() {
        weatherStation.setMeasurements(75.0f, 60.0f, 30.0f);
        assertEquals("", outputCaptor.toString(),
                "No output expected when no observers are registered");
    }

    @Test
    void testOnlyRegisteredObserversReceiveNotification() {
        // Only CurrentConditionsDisplay registered — others must stay silent
        weatherStation.registerObserver(new CurrentConditionsDisplay());
        weatherStation.setMeasurements(75.0f, 60.0f, 30.0f);
        String output = outputCaptor.toString();

        assertTrue(output.contains("Current conditions:"));
        assertFalse(output.contains("Weather statistics:"));
        assertFalse(output.contains("Forecast:"));
        assertFalse(output.contains("Heat index:"));
    }

    @Test
    void testRemoveObserverStopsNotifications() {
        CurrentConditionsDisplay display = new CurrentConditionsDisplay();
        weatherStation.registerObserver(display);

        // Verify it receives the first update
        weatherStation.setMeasurements(75.0f, 60.0f, 30.0f);
        assertTrue(outputCaptor.toString().contains("Current conditions: 75.0"));

        // Remove and verify it no longer receives updates
        outputCaptor.reset();
        weatherStation.removeObserver(display);
        weatherStation.setMeasurements(80.0f, 65.0f, 30.4f);
        assertEquals("", outputCaptor.toString(),
                "Removed observer must not produce any output");
    }

    @Test
    void testRegisteringTheSameObserverTwiceDoesNotDuplicateOutput() {
        CurrentConditionsDisplay display = new CurrentConditionsDisplay();
        weatherStation.registerObserver(display);
        weatherStation.registerObserver(display); // duplicate — should be ignored

        weatherStation.setMeasurements(75.0f, 60.0f, 30.0f);
        String output = outputCaptor.toString();

        long lineCount = output.lines()
                .filter(l -> l.contains("Current conditions:"))
                .count();
        assertEquals(1, lineCount, "Duplicate registration must not cause duplicate notifications");
    }

    @Test
    void testRemovingUnregisteredObserverIsHarmless() {
        CurrentConditionsDisplay display = new CurrentConditionsDisplay();
        // Never registered — remove should not throw
        assertDoesNotThrow(() -> weatherStation.removeObserver(display));
    }

    @Test
    void testDynamicallyAddingObserverMidSession() {
        CurrentConditionsDisplay conditionsDisplay = new CurrentConditionsDisplay();
        ForecastDisplay forecastDisplay = new ForecastDisplay();

        weatherStation.registerObserver(conditionsDisplay);
        weatherStation.setMeasurements(75.0f, 60.0f, 30.0f);
        assertFalse(outputCaptor.toString().contains("Forecast:"),
                "Forecast display not yet registered");

        // Add forecast display for subsequent updates only
        outputCaptor.reset();
        weatherStation.registerObserver(forecastDisplay);
        weatherStation.setMeasurements(80.0f, 65.0f, 29.5f);
        String output = outputCaptor.toString();

        assertTrue(output.contains("Current conditions:"));
        assertTrue(output.contains("Forecast:"));
    }

    // =========================================================================
    // Group 3 — Individual display logic (unit tests for each concrete observer)
    // =========================================================================

    @Test
    void testCurrentConditionsDisplayFormat() {
        CurrentConditionsDisplay display = new CurrentConditionsDisplay();
        display.update(80.0f, 65.0f, 30.0f);
        assertEquals("Current conditions: 80.0\u00b0F, 65.0% humidity", display.display());
    }

    @Test
    void testStatisticsDisplayFormat() {
        StatisticsDisplay display = new StatisticsDisplay();
        display.update(80.0f, 65.0f, 30.0f);
        assertEquals("Weather statistics: Avg/Max/Min temperature = 78.0/82.0/75.0",
                display.display());
    }

    @Test
    void testForecastDisplayLowPressure() {
        ForecastDisplay display = new ForecastDisplay();
        display.update(75.0f, 60.0f, 29.0f);
        assertTrue(display.display().contains("Watch out for cooler, rainy weather"));
    }

    @Test
    void testForecastDisplayHighPressure() {
        ForecastDisplay display = new ForecastDisplay();
        display.update(75.0f, 60.0f, 30.5f);
        assertTrue(display.display().contains("Improving weather on the way!"));
    }

    @Test
    void testForecastDisplayAtThreshold() {
        // Exactly at 29.92 — should show "Improving" (not strictly less than)
        ForecastDisplay display = new ForecastDisplay();
        display.update(75.0f, 60.0f, 29.92f);
        assertTrue(display.display().contains("Improving weather on the way!"));
    }

    @Test
    void testHeatIndexDisplayFormat() {
        HeatIndexDisplay display = new HeatIndexDisplay();
        display.update(85.0f, 75.0f, 30.0f);
        assertEquals("Heat index: 80.0", display.display());
    }
}