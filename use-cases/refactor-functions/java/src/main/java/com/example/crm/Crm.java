package com.example.crm;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Crm {
    private static final Logger logger = LogManager.getLogger(Crm.class);
    private final CustomerRepository customerRepository = new CustomerRepository();

    /** Outcome of email field validation, used to drive the main processing loop. */
    private enum EmailValidationStatus { VALID, INVALID, SKIP_DUPLICATE }

    /**
     * Processes raw customer data, validates it, transforms it, and loads it into the database.
     * Delegates each distinct responsibility to a focused helper method.
     */
    public Map<String, Object> processCustomerData(List<Map<String, Object>> rawData,
                                                   String source,
                                                   CustomerProcessingOptions options) {
        long startTime = System.currentTimeMillis();

        if (rawData == null || rawData.isEmpty()) {
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("status", "error");
            errorResult.put("message", "No data provided for processing");
            return errorResult;
        }

        logger.info("Starting to process {} customer records from source: {}", rawData.size(), source);

        // --- 1. Deduplication setup ---
        Set<String> existingEmails = new HashSet<>();
        Set<String> existingPhones = new HashSet<>();
        if (options.isPerformDeduplication()) {
            try {
                loadDeduplicationData(existingEmails, existingPhones);
            } catch (Exception e) {
                logger.error("Error loading existing customers for deduplication: {}", e.getMessage());
                Map<String, Object> errorResult = new HashMap<>();
                errorResult.put("status", "error");
                errorResult.put("message", "Failed to load existing customers for deduplication");
                errorResult.put("error", e.getMessage());
                return errorResult;
            }
        }

        // --- 2. Per-record processing loop ---
        List<Map<String, Object>> validRecords = new ArrayList<>();
        List<Map<String, Object>> invalidRecords = new ArrayList<>();
        List<Map<String, Object>> duplicateRecords = new ArrayList<>();
        Map<String, Integer> errorCounts = new HashMap<>();
        int totalProcessed = 0, totalSuccess = 0, totalSkipped = 0, totalErrors = 0;

        for (Map<String, Object> record : rawData) {
            totalProcessed++;

            if (options.getMaxErrorCount() > 0 && totalErrors >= options.getMaxErrorCount()) {
                logger.warn("Maximum error threshold reached ({}). Skipping remaining records.",
                        options.getMaxErrorCount());
                totalSkipped = rawData.size() - totalProcessed + 1;
                break;
            }

            Map<String, Object> processedRecord = new HashMap<>(record);
            try {
                processedRecord = preprocessRecord(processedRecord, source);
                List<String> recordErrors = new ArrayList<>();

                EmailValidationStatus emailStatus = validateAndProcessEmail(
                        processedRecord, recordErrors, validRecords, existingEmails, options);

                if (emailStatus == EmailValidationStatus.SKIP_DUPLICATE) {
                    duplicateRecords.add(processedRecord);
                    totalSkipped++;
                    continue;
                }

                boolean isValid = (emailStatus == EmailValidationStatus.VALID);
                isValid &= validateAndProcessName(processedRecord, recordErrors, "firstName");
                isValid &= validateAndProcessName(processedRecord, recordErrors, "lastName");
                isValid &= validateAndProcessPhone(processedRecord, recordErrors, validRecords, existingPhones, options);
                isValid &= validateAndProcessAddress(processedRecord, recordErrors);
                isValid &= validateAndProcessDateOfBirth(processedRecord, recordErrors);
                isValid &= applyCustomValidation(processedRecord, recordErrors, options);

                if (isValid) {
                    validRecords.add(processedRecord);
                    totalSuccess++;
                } else {
                    processedRecord.put("errors", recordErrors);
                    invalidRecords.add(processedRecord);
                    totalErrors++;
                    for (String error : recordErrors) {
                        String errorType = error.split(":")[0].trim();
                        errorCounts.put(errorType, errorCounts.getOrDefault(errorType, 0) + 1);
                    }
                }

            } catch (Exception e) {
                logger.error("Unexpected error processing record {}: {}", totalProcessed, e.getMessage());
                e.printStackTrace();
                processedRecord.put("errors", Collections.singletonList("Processing error: " + e.getMessage()));
                invalidRecords.add(processedRecord);
                totalErrors++;
                errorCounts.put("Processing error", errorCounts.getOrDefault("Processing error", 0) + 1);
            }
        }

        // --- 3. Persist valid records ---
        if (options.isSaveToDatabase() && !validRecords.isEmpty()) {
            try {
                persistValidRecords(validRecords, source);
                logger.info("Successfully saved {} customer records to database", validRecords.size());
            } catch (Exception e) {
                logger.error("Error saving records to database: {}", e.getMessage());
                Map<String, Object> errorResult = new HashMap<>();
                errorResult.put("status", "error");
                errorResult.put("message", "Failed to save valid records to database");
                errorResult.put("error", e.getMessage());
                return errorResult;
            }
        }

        // --- 4. Build and return the processing report ---
        long processingTime = System.currentTimeMillis() - startTime;
        logger.info("Customer data processing completed. Total: {}, Success: {}, Error: {}, Skipped: {}, Time: {} ms",
                rawData.size(), totalSuccess, totalErrors, totalSkipped, processingTime);

        return buildProcessingReport(source, rawData.size(), totalProcessed, totalSuccess, totalErrors,
                totalSkipped, duplicateRecords, errorCounts, processingTime, validRecords, invalidRecords, options);
    }

    // -------------------------------------------------------------------------
    // Extracted helper methods — each handles one distinct responsibility
    // -------------------------------------------------------------------------

    /**
     * Populates the given sets with emails and phone numbers already stored in the repository,
     * so that incoming records can be checked for duplicates.
     */
    private void loadDeduplicationData(Set<String> existingEmails, Set<String> existingPhones) {
        List<Customer> existingCustomers = customerRepository.findAll();
        for (Customer customer : existingCustomers) {
            if (customer.getEmail() != null && !customer.getEmail().isEmpty()) {
                existingEmails.add(customer.getEmail().toLowerCase());
            }
            if (customer.getPhoneNumber() != null && !customer.getPhoneNumber().isEmpty()) {
                existingPhones.add(normalizePhoneNumber(customer.getPhoneNumber()));
            }
        }
        logger.info("Loaded {} existing customers for deduplication", existingCustomers.size());
    }

    /**
     * Routes a raw record through the appropriate source-specific pre-processor
     * (CSV, API, or manual entry) before field-level validation begins.
     */
    private Map<String, Object> preprocessRecord(Map<String, Object> record, String source) {
        switch (source) {
            case "csv":    return preprocessCsvRecord(record);
            case "api":    return preprocessApiRecord(record);
            case "manual": return preprocessManualRecord(record);
            default:       return record;
        }
    }

    /**
     * Validates the email field: checks presence, format, and (optionally) uniqueness.
     * Returns SKIP_DUPLICATE when the record should be silently skipped rather than flagged.
     */
    private EmailValidationStatus validateAndProcessEmail(Map<String, Object> record,
                                                          List<String> errors,
                                                          List<Map<String, Object>> validRecords,
                                                          Set<String> existingEmails,
                                                          CustomerProcessingOptions options) {
        if (!record.containsKey("email") || record.get("email") == null
                || record.get("email").toString().trim().isEmpty()) {
            errors.add("Missing required field: email");
            return EmailValidationStatus.INVALID;
        }

        String email = record.get("email").toString().trim().toLowerCase();
        record.put("email", email);

        if (!isValidEmail(email)) {
            errors.add("Invalid email format: " + email);
            return EmailValidationStatus.INVALID;
        }

        if (options.isPerformDeduplication() && isEmailDuplicate(email, validRecords, existingEmails)) {
            errors.add("Duplicate email: " + email);
            return options.isDuplicatesAreErrors()
                    ? EmailValidationStatus.INVALID
                    : EmailValidationStatus.SKIP_DUPLICATE;
        }

        return EmailValidationStatus.VALID;
    }

    /** Returns true if the email already appears in the current batch or the existing-emails set. */
    private boolean isEmailDuplicate(String email,
                                     List<Map<String, Object>> validRecords,
                                     Set<String> existingEmails) {
        for (Map<String, Object> validRecord : validRecords) {
            if (email.equals(validRecord.get("email").toString().toLowerCase())) {
                return true;
            }
        }
        return existingEmails.contains(email);
    }

    /**
     * Validates and capitalises the first letter of a required name field (firstName or lastName).
     * Appends an error and returns false when the field is absent or null.
     */
    private boolean validateAndProcessName(Map<String, Object> record,
                                           List<String> errors,
                                           String fieldName) {
        if (!record.containsKey(fieldName) || record.get(fieldName) == null) {
            errors.add("Missing required field: " + fieldName);
            return false;
        }
        String name = record.get(fieldName).toString().trim();
        if (!name.isEmpty()) {
            name = name.substring(0, 1).toUpperCase() + (name.length() > 1 ? name.substring(1) : "");
        }
        record.put(fieldName, name);
        return true;
    }

    /**
     * Validates, normalises, and formats the optional phone field.
     * Also performs duplicate detection when deduplication is enabled.
     */
    private boolean validateAndProcessPhone(Map<String, Object> record,
                                            List<String> errors,
                                            List<Map<String, Object>> validRecords,
                                            Set<String> existingPhones,
                                            CustomerProcessingOptions options) {
        if (!record.containsKey("phone") || record.get("phone") == null
                || record.get("phone").toString().trim().isEmpty()) {
            return true; // phone is optional
        }

        String phone = record.get("phone").toString().trim();
        String normalizedPhone = normalizePhoneNumber(phone);

        if (!isValidPhoneNumber(normalizedPhone)) {
            errors.add("Invalid phone number format: " + phone);
            return false;
        }

        record.put("phone", formatPhoneNumber(normalizedPhone));

        if (options.isPerformDeduplication()
                && isPhoneDuplicate(normalizedPhone, validRecords, existingPhones)) {
            errors.add("Duplicate phone number: " + phone);
            if (options.isDuplicatesAreErrors()) {
                return false;
            }
        }

        return true;
    }

    /** Returns true if the normalised phone already appears in the current batch or existing set. */
    private boolean isPhoneDuplicate(String normalizedPhone,
                                     List<Map<String, Object>> validRecords,
                                     Set<String> existingPhones) {
        for (Map<String, Object> validRecord : validRecords) {
            if (validRecord.containsKey("phone")
                    && normalizedPhone.equals(normalizePhoneNumber(validRecord.get("phone").toString()))) {
                return true;
            }
        }
        return existingPhones.contains(normalizedPhone);
    }

    /**
     * Parses and normalises the optional address field.
     * Accepts either a Map or a flat String and validates state/zip/country when present.
     */
    @SuppressWarnings("unchecked")
    private boolean validateAndProcessAddress(Map<String, Object> record, List<String> errors) {
        if (!record.containsKey("address") || record.get("address") == null) {
            return true; // address is optional
        }

        Map<String, Object> addressData;
        if (record.get("address") instanceof Map) {
            addressData = (Map<String, Object>) record.get("address");
        } else if (record.get("address") instanceof String) {
            addressData = parseAddressString(record.get("address").toString());
        } else {
            errors.add("Invalid address format");
            return false;
        }

        boolean isValid = true;
        Map<String, Object> normalizedAddress = new HashMap<>();

        if (addressData.containsKey("street") && addressData.get("street") != null) {
            normalizedAddress.put("street", addressData.get("street").toString().trim());
        }

        if (addressData.containsKey("city") && addressData.get("city") != null) {
            normalizedAddress.put("city", capitalizeWords(addressData.get("city").toString().trim()));
        }

        if (addressData.containsKey("state") && addressData.get("state") != null) {
            String state = addressData.get("state").toString().trim().toUpperCase();
            if (addressData.containsKey("country")) {
                String country = addressData.get("country").toString();
                if ((country.equals("US") || country.equals("CA"))
                        && !isValidStateOrProvince(state, country)) {
                    errors.add("Invalid state/province: " + state);
                    isValid = false;
                }
            }
            normalizedAddress.put("state", state);
        }

        if (addressData.containsKey("zip") && addressData.get("zip") != null) {
            String zip = addressData.get("zip").toString().trim();
            normalizedAddress.put("zip", zip);
            if (addressData.containsKey("country")) {
                String country = addressData.get("country").toString();
                if (!isValidPostalCode(zip, country)) {
                    errors.add("Invalid postal code format for " + country + ": " + zip);
                    isValid = false;
                }
            }
        }

        if (addressData.containsKey("country") && addressData.get("country") != null) {
            String country = addressData.get("country").toString().trim();
            if (country.length() <= 3) {
                String fullName = getCountryNameFromCode(country);
                if (fullName != null) {
                    country = fullName;
                } else {
                    errors.add("Invalid country code: " + country);
                    isValid = false;
                }
            }
            normalizedAddress.put("country", country);
        }

        record.put("address", normalizedAddress);
        return isValid;
    }

    /**
     * Parses the optional dateOfBirth field, validates the age is between 18 and 120,
     * and re-formats it as yyyy-MM-dd.
     */
    private boolean validateAndProcessDateOfBirth(Map<String, Object> record, List<String> errors) {
        if (!record.containsKey("dateOfBirth") || record.get("dateOfBirth") == null
                || record.get("dateOfBirth").toString().trim().isEmpty()) {
            return true; // dateOfBirth is optional
        }

        String dobString = record.get("dateOfBirth").toString().trim();
        try {
            Date dob = parseDate(dobString);
            int birthYear = Calendar.getInstance(/* reuse */ ).get(Calendar.YEAR);
            Calendar cal = Calendar.getInstance();
            cal.setTime(dob);
            birthYear = cal.get(Calendar.YEAR);
            int currentYear = Calendar.getInstance().get(Calendar.YEAR);

            if (currentYear - birthYear > 120 || currentYear - birthYear < 18) {
                errors.add("Invalid date of birth (age must be between 18 and 120): " + dobString);
                return false;
            }
            record.put("dateOfBirth", formatDate(dob));
            return true;
        } catch (ParseException e) {
            errors.add("Invalid date format for date of birth: " + dobString);
            return false;
        }
    }

    /**
     * Delegates record-level validation to the configured custom validator (if any).
     * Returns false and appends errors when the validator finds problems.
     */
    private boolean applyCustomValidation(Map<String, Object> record,
                                          List<String> errors,
                                          CustomerProcessingOptions options) {
        if (options.getCustomValidator() == null) {
            return true;
        }
        List<String> customErrors = options.getCustomValidator().validate(record);
        if (customErrors != null && !customErrors.isEmpty()) {
            errors.addAll(customErrors);
            return false;
        }
        return true;
    }

    /**
     * Converts valid records to Customer entities and saves them to the repository.
     * Stamps each entity with its data source and creation timestamp.
     */
    private void persistValidRecords(List<Map<String, Object>> validRecords, String source) {
        List<Customer> customers = new ArrayList<>();
        for (Map<String, Object> record : validRecords) {
            Customer customer = mapToCustomerEntity(record);
            customer.setDataSource(source);
            customer.setCreatedAt(new Date());
            customers.add(customer);
        }
        customerRepository.saveAll(customers);
    }

    /**
     * Assembles the final result map from processing counters and record lists.
     * Optionally includes the valid, invalid, and duplicate record lists in the response.
     */
    private Map<String, Object> buildProcessingReport(String source,
                                                       int totalRecords,
                                                       int totalProcessed,
                                                       int totalSuccess,
                                                       int totalErrors,
                                                       int totalSkipped,
                                                       List<Map<String, Object>> duplicateRecords,
                                                       Map<String, Integer> errorCounts,
                                                       long processingTimeMs,
                                                       List<Map<String, Object>> validRecords,
                                                       List<Map<String, Object>> invalidRecords,
                                                       CustomerProcessingOptions options) {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");
        result.put("source", source);
        result.put("totalRecords", totalRecords);
        result.put("processedCount", totalProcessed);
        result.put("successCount", totalSuccess);
        result.put("errorCount", totalErrors);
        result.put("skippedCount", totalSkipped);
        result.put("duplicateCount", duplicateRecords.size());
        result.put("processingTimeMs", processingTimeMs);
        result.put("errorsByType", errorCounts);

        if (options.isIncludeRecordsInResponse()) {
            if (options.isIncludeValidRecords())     result.put("validRecords", validRecords);
            if (options.isIncludeInvalidRecords())   result.put("invalidRecords", invalidRecords);
            if (options.isIncludeDuplicateRecords()) result.put("duplicateRecords", duplicateRecords);
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Low-level utility stubs (source pre-processors, format helpers, etc.)
    // -------------------------------------------------------------------------

    private Map<String, Object> preprocessCsvRecord(Map<String, Object> record) {
// Handle CSV-specific preprocessing
        return record;
    }

    private Map<String, Object> preprocessApiRecord(Map<String, Object> record) {
// Handle API-specific preprocessing
        return record;
    }

    private Map<String, Object> preprocessManualRecord(Map<String, Object> record) {
// Handle manually entered data formatting
        return record;
    }

    private boolean isValidEmail(String email) {
// Validate email format
        return email.matches("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,6}$");
    }

    private String normalizePhoneNumber(String phone) {
// Remove non-digit characters
        return phone.replaceAll("[^0-9+]", "");
    }

    private boolean isValidPhoneNumber(String phone) {
// Basic phone validation
        return phone.matches("^\\+?[0-9]{10,15}$");
    }

    private String formatPhoneNumber(String phone) {
// Format phone number consistently
        return phone;
    }

    private Map<String, Object> parseAddressString(String addressString) {
// Parse address string into components
        Map<String, Object> addressComponents = new HashMap<>();
// Implementation omitted
        return addressComponents;
    }

    private String capitalizeWords(String text) {
// Capitalize first letter of each word
        if (text == null || text.isEmpty()) {
            return text;
        }

        StringBuilder result = new StringBuilder();
        String[] words = text.split("\\s");

        for (String word : words) {
            if (!word.isEmpty()) {
                result.append(Character.toUpperCase(word.charAt(0)))
                        .append(word.substring(1).toLowerCase())
                        .append(" ");
            }
        }

        return result.toString().trim();
    }

    private boolean isValidStateOrProvince(String code, String country) {
        return true; // Implementation omitted
    }

    private boolean isValidPostalCode(String postalCode, String country) {
        return true; // Implementation omitted
    }

    private String getCountryNameFromCode(String countryCode) {
        return countryCode; // Implementation omitted
    }

    private Date parseDate(String dateString) throws ParseException {
        return new SimpleDateFormat("yyyy-MM-dd").parse(dateString);
    }

    private String formatDate(Date date) {
        return new SimpleDateFormat("yyyy-MM-dd").format(date);
    }

    private Customer mapToCustomerEntity(Map<String, Object> record) {
// Map record to Customer entity
        Customer customer = new Customer("Test", "Test@test.com", "Test");
// Implementation omitted
        return customer;
    }
}
